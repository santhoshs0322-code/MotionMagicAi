"""Owner-managed showcase media and the small public home feed."""
import uuid

from django import forms
from django.conf import settings
from django.core.cache import cache
from django.db.models import Q
from django.http import FileResponse, Http404, HttpResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.views.decorators.http import require_http_methods, require_POST
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.views import APIView

from . import storage
from .models import OwnerAudit, Rate, ShowcaseTemplate, ShowcaseBanner
from .owner import owner_only
from . import bundled_templates, banner_media
from .limits import CREATION_PROMPT_MAX_LENGTH

FEED_CACHE_KEY = "motion:showcase:feed:v3"
MAX_SHOWCASE_TEMPLATES = 100


IMAGE_TYPES = {b"\xff\xd8\xff": ("image/jpeg", "jpg"), b"\x89PNG\r\n\x1a\n": ("image/png", "png")}


def image_type(file):
    header = file.read(16)
    file.seek(0)
    for magic, result in IMAGE_TYPES.items():
        if header.startswith(magic):
            return result
    if header.startswith(b"RIFF") and header[8:12] == b"WEBP":
        return "image/webp", "webp"
    raise forms.ValidationError("Use a JPG, PNG or WebP image.")


def video_type(file):
    header = file.read(16)
    file.seek(0)
    if header[4:8] != b"ftyp" or header[8:12] not in (b"isom", b"iso2", b"mp41", b"mp42", b"avc1", b"M4V "):
        raise forms.ValidationError("Use an MP4 video encoded for Android playback.")
    return "video/mp4", "mp4"


class TemplateRateChoice(forms.ModelChoiceField):
    def label_from_instance(self, rate):
        config = rate.config if isinstance(rate.config, dict) else {}
        parts = [rate.title]
        size = config.get("resolution", config.get("image_size", ""))
        if size and str(size).casefold() not in rate.title.casefold():
            parts.append(str(size))
        if rate.kind == "video":
            parts.append("Audio" if config.get("generated_audio") else "Silent")
        if not rate.enabled:
            parts.append("Disabled")
        return " · ".join(parts)


class ShowcaseForm(forms.ModelForm):
    category_action = forms.ChoiceField(choices=(("existing", "Choose existing"), ("new", "Create new")))
    category = forms.ChoiceField(required=False, label="Existing category")
    new_category = forms.CharField(required=False, max_length=40, label="New category name",
                                   widget=forms.TextInput(attrs={"placeholder": "For example, Fantasy portraits"}))
    rate = TemplateRateChoice(queryset=Rate.objects.filter(kind__in=("image", "video")).order_by("kind", "title", "slug"),
                              label="Model and quality", empty_label="Choose a model and quality")
    poster = forms.FileField(required=False, help_text="Portrait or landscape JPG, PNG or WebP; up to 5 MB.")
    sample_video = forms.FileField(required=False, help_text="MP4 example shown to users; up to 50 MB. Keep it short for fast previews.")
    example_input = forms.FileField(required=False, help_text="Optional example reference image; users choose their own image.")

    class Meta:
        model = ShowcaseTemplate
        fields = ("title", "category", "prompt", "mode", "rate", "aspect_ratio", "duration", "requires_reference", "position", "featured", "published")
        widgets = {"prompt": forms.Textarea(attrs={"rows": 4})}

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        # Both CSS-switched controls submit without JavaScript; validate only the chosen branch.
        if self.is_bound:
            self.data = self.data.copy()
            action = self.data.get(self.add_prefix("category_action"))
            if action == "new":
                self.data[self.add_prefix("category")] = ""
            elif action == "existing":
                self.data[self.add_prefix("new_category")] = ""
        self.category_names = {}
        for name in ShowcaseTemplate.objects.order_by("category").values_list("category", flat=True).distinct():
            normalized = " ".join(name.split())
            if normalized:
                self.category_names.setdefault(normalized.casefold(), normalized)
        self.fields["category"].choices = [("", "Choose a category")] + [
            (name, name) for name in sorted(self.category_names.values(), key=str.casefold)]
        self.initial["category_action"] = "existing" if self.category_names else "new"
        if self.instance.category:
            self.initial["category"] = self.category_names.get(" ".join(self.instance.category.split()).casefold(), self.instance.category)
        self.fields["poster"].label = "Template image"
        self.fields["poster"].widget.attrs["accept"] = "image/jpeg,image/png,image/webp"
        self.fields["example_input"].widget.attrs["accept"] = "image/jpeg,image/png,image/webp"
        self.fields["sample_video"].widget.attrs["accept"] = "video/mp4"
        self.fields["mode"].label = "Creation type"
        self.fields["prompt"].help_text = f"The prompt filled in when a user chooses this template. Up to {CREATION_PROMPT_MAX_LENGTH:,} characters."
        self.fields["duration"].help_text = "Seconds for video templates; ignored for Create Image."

    def clean(self):
        data = super().clean()
        if data.get("category_action") == "new":
            name = " ".join(data.get("new_category", "").split())
            if name:
                data["category"] = self.category_names.get(name.casefold(), name)
            else:
                self.add_error("new_category", "Enter a name for the new category.")
        elif data.get("category_action") == "existing" and not data.get("category"):
            self.add_error("category", "Choose an existing category, or select Create new.")
        rate = data.get("rate")
        mode = data.get("mode")
        if rate:
            if (mode == "create_image") != (rate.kind == "image") or rate.kind not in ("image", "video"):
                self.add_error("rate", "Choose an image rate for Create Image or a video rate for video templates.")
            config = rate.config if isinstance(rate.config, dict) else {}
            if config.get("reference_video"):
                self.add_error("rate", "Video-reference rates are not supported by this image-input template flow.")
            if data.get("aspect_ratio") not in config.get("aspect_ratios", []):
                self.add_error("aspect_ratio", "This aspect ratio is not supported by the chosen rate.")
            if rate.kind == "video" and data.get("duration") not in config.get("durations", []):
                self.add_error("duration", "This duration is not supported by the chosen rate.")
        if data.get("requires_reference") and mode == "text_to_video":
            self.add_error("requires_reference", "Use Image to Video or Create Image when the customer's photo is required.")
        if data.get("published"):
            if not (self.files.get("poster") or self.instance.poster_key):
                self.add_error("poster", "Upload a poster before publishing.")
            if mode != "create_image" and not (self.files.get("sample_video") or self.instance.video_key):
                self.add_error("sample_video", "Upload an example video before publishing.")
        for name in ("poster", "example_input", "sample_video"):
            file = self.files.get(name)
            if not file:
                continue
            limit = 50 * 1024 * 1024 if name == "sample_video" else 5 * 1024 * 1024
            if file.size <= 0 or file.size > limit:
                self.add_error(name, f"File must be under {limit // (1024 * 1024)} MB.")
            else:
                try:
                    video_type(file) if name == "sample_video" else image_type(file)
                except forms.ValidationError as error:
                    self.add_error(name, error)
        return data


def media_url(key, s3=None):
    if not key:
        return ""
    if key.startswith("builtin:"):
        entry = bundled_templates.poster(key)
        if entry is None:
            raise ValueError("Unknown bundled artwork")
        return f"/api/v1/templates/artwork/{entry['file']}?v={entry['sha256']}"
    if not key.startswith("catalog/templates/") or ".." in key:
        raise ValueError("Invalid showcase media key")
    return (s3 or storage.client()).generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": key}, ExpiresIn=3600)


def upload_media(item, name, file):
    mime, extension = video_type(file) if name == "sample_video" else image_type(file)
    prefix = "optimized-v1-" if name == "banner" else ""
    key = f"catalog/templates/{item.pk}/{name}/{prefix}{uuid.uuid4()}.{extension}"
    storage.client().upload_fileobj(file, settings.S3_BUCKET, key, ExtraArgs={"ContentType": mime, "CacheControl": "public, max-age=86400"})
    return key


@owner_only
@require_http_methods(["GET", "POST"])
def owner_templates(request, identifier=None):
    item = get_object_or_404(ShowcaseTemplate, pk=identifier) if identifier else ShowcaseTemplate()
    form = ShowcaseForm(request.POST or None, request.FILES or None, instance=item)
    if request.method == "POST" and form.is_valid():
        updated = form.save(commit=False)
        keys = []
        try:
            for field, key_field in (("poster", "poster_key"), ("sample_video", "video_key"), ("example_input", "example_input_key")):
                file = request.FILES.get(field)
                if file:
                    key = upload_media(updated, field, file)
                    keys.append(key)
                    setattr(updated, key_field, key)
            updated.save()
            cache.delete(FEED_CACHE_KEY)
            OwnerAudit.objects.create(owner=request.user, action="showcase_save", reference=str(updated.pk))
            return redirect("owner-templates")
        except Exception:
            for key in keys:
                try:
                    storage.client().delete_object(Bucket=settings.S3_BUCKET, Key=key)
                except Exception:
                    pass
            form.add_error(None, "Upload failed. Check S3 access and retry.")
    items = list(ShowcaseTemplate.objects.select_related("rate").order_by("category", "position", "-created_at")[:MAX_SHOWCASE_TEMPLATES])
    s3 = storage.client() if any((row.poster_key and not row.poster_key.startswith("builtin:")) or row.video_key for row in items) else None
    for row in items:
        row.poster_url = media_url(row.poster_key, s3) if row.poster_key else ""
        row.video_url = media_url(row.video_key, s3) if row.video_key else ""
    preview = sorted((row for row in items if row.published and row.rate.enabled and row.poster_key and
                      (row.mode == "create_image" or row.video_key)), key=lambda row: (row.position, -row.created_at.timestamp()))[:MAX_SHOWCASE_TEMPLATES]
    groups = {}
    for row in preview:
        groups.setdefault(row.category, []).append(row)
    featured = next((row for row in preview if row.featured), next(iter(preview), None))
    banners = banner_rows()
    durations = [max(3, min(15, row["display_seconds"])) for row in banners]
    banner_cycle = sum(durations) or 1
    elapsed = 0
    for row, seconds in zip(banners, durations):
        row["preview_start"] = f"{100 * elapsed / banner_cycle:.6f}"
        elapsed += seconds
        row["preview_end"] = f"{100 * elapsed / banner_cycle:.6f}"
    form_sections = [
        ("2", "Image and title", [form[name] for name in ("poster", "title", "example_input", "sample_video")]),
        ("3", "Prompt and model", [form[name] for name in ("prompt", "mode", "rate", "aspect_ratio", "duration", "requires_reference")]),
        ("4", "Publishing", [form[name] for name in ("position", "featured", "published")]),
    ]
    return render(request, "owner/showcase.html", {"form": form, "items": items, "featured": featured,
                  "preview_groups": list(groups.items()), "banners": banners, "banner_cycle": banner_cycle,
                  "editing": bool(identifier), "form_sections": form_sections})


@owner_only
@require_POST
def unpublish_template(request, identifier):
    item = get_object_or_404(ShowcaseTemplate, pk=identifier)
    item.published = False
    item.save(update_fields=["published", "updated_at"])
    cache.delete(FEED_CACHE_KEY)
    OwnerAudit.objects.create(owner=request.user, action="showcase_unpublish", reference=str(item.pk))
    return redirect("owner-templates")


class ShowcaseFeed(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def get(self, request):
        cached = cache.get(FEED_CACHE_KEY)
        if cached is not None:
            response = Response({"ok": True, **cached})
            response["Cache-Control"] = "public, max-age=30"
            return response
        rows = ShowcaseTemplate.objects.select_related("rate").filter(
            published=True, rate__enabled=True, poster_key__gt="",
        ).filter(Q(mode="create_image") | ~Q(video_key=""))
        if not settings.S3_BUCKET:
            rows = rows.filter(poster_key__startswith="builtin:", video_key="", example_input_key="")
        rows = rows.order_by("position", "-created_at")[:MAX_SHOWCASE_TEMPLATES]
        rows = list(rows)
        s3 = storage.client() if any((row.poster_key and not row.poster_key.startswith("builtin:")) or row.video_key or row.example_input_key for row in rows) else None
        data = []
        for row in rows:
            config = row.rate.config if isinstance(row.rate.config, dict) else {}
            data.append({
                "id": str(row.pk), "title": row.title, "category": row.category, "prompt": row.prompt,
                "mode": row.mode, "aspect_ratio": row.aspect_ratio, "duration": row.duration,
                "model_id": config.get("app_model_id", ""),
                "resolution": config.get("resolution", config.get("image_size", "")),
                "generated_audio": config.get("generated_audio", False),
                "featured": row.featured, "updated_at": row.updated_at.isoformat(),
                "requires_reference": row.requires_reference,
                "bundled_poster": bundled_templates.poster(row.poster_key),
                "poster_url": media_url(row.poster_key, s3), "video_url": media_url(row.video_key, s3),
                "example_input_url": media_url(row.example_input_key, s3),
            })
        catalogue = {"templates": data, "banners": banner_rows()}
        cache.set(FEED_CACHE_KEY, catalogue, 30)
        response = Response({"ok": True, **catalogue})
        response["Cache-Control"] = "public, max-age=30"
        return response


@require_http_methods(["GET", "HEAD"])
def bundled_artwork(request, filename):
    entry = bundled_templates.poster("builtin:" + filename)
    if entry is None:
        raise Http404()
    etag = '"' + entry["sha256"] + '"'
    if request.headers.get("If-None-Match") == etag:
        response = HttpResponse(status=304)
    else:
        response = FileResponse(bundled_templates.artwork_path(entry["file"]).open("rb"), content_type="image/jpeg")
    response["ETag"] = etag
    response["Cache-Control"] = "public, max-age=86400"
    return response


def banner_rows():
    rows = ShowcaseBanner.objects.filter(published=True).exclude(image_key="")
    if not settings.S3_BUCKET:
        rows = rows.filter(image_key__startswith="builtin:")
    rows = list(rows[:20])
    s3 = storage.client() if any(not row.image_key.startswith("builtin:") for row in rows) else None
    return [{"id": str(row.pk), "title": row.title, "destination": row.destination, "position": row.position,
             "display_seconds": row.display_seconds, "updated_at": row.updated_at.isoformat(),
             "image_url": media_url(row.image_key, s3), "bundled_image": bundled_templates.poster(row.image_key)} for row in rows]


class BannerForm(forms.ModelForm):
    image = forms.FileField(required=False, help_text="Upload JPG, PNG or WebP up to 5 MB, ideally 3:1. Automatically resized to fit 1500 × 500 and compressed below 300 KB.")
    display_seconds = forms.IntegerField(min_value=3, max_value=15, initial=4, help_text="Seconds before the app advances to the next banner (3–15).")

    class Meta:
        model = ShowcaseBanner
        fields = ("title", "destination", "position", "display_seconds", "published")

    def clean(self):
        data = super().clean()
        image = self.files.get("image")
        if image:
            if not 0 < image.size <= 5 * 1024**2:
                self.add_error("image", "Choose an image under 5 MB.")
            else:
                try:
                    image_type(image)
                    self.optimized_image = banner_media.optimize_banner(image)
                except forms.ValidationError as error:
                    self.add_error("image", error)
        if data.get("published") and not (image or self.instance.image_key):
            self.add_error("image", "Upload banner artwork before publishing.")
        return data


@owner_only
@require_http_methods(["GET", "POST"])
def owner_banners(request, identifier=None):
    item = get_object_or_404(ShowcaseBanner, pk=identifier) if identifier else ShowcaseBanner()
    form = BannerForm(request.POST or None, request.FILES or None, instance=item)
    if request.method == "POST" and form.is_valid():
        updated = form.save(commit=False)
        uploaded = None
        try:
            if request.FILES.get("image"):
                uploaded = upload_media(updated, "banner", form.optimized_image)
                updated.image_key = uploaded
            updated.save()
            cache.delete(FEED_CACHE_KEY)
            OwnerAudit.objects.create(owner=request.user, action="banner_save", reference=str(updated.pk))
            return redirect("owner-banners")
        except Exception:
            if uploaded:
                try:
                    storage.client().delete_object(Bucket=settings.S3_BUCKET, Key=uploaded)
                except Exception:
                    pass
            form.add_error(None, "Could not save banner. Check storage and retry.")
    rows = list(ShowcaseBanner.objects.all()[:100])
    s3 = storage.client() if any(row.image_key and not row.image_key.startswith("builtin:") for row in rows) else None
    for row in rows:
        row.image_url = media_url(row.image_key, s3)
    return render(request, "owner/banners.html", {"form": form, "banners": rows, "editing": bool(identifier)})
