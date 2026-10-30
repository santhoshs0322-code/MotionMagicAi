"""Pinned fal contracts. Clients can select a rate, never a provider endpoint.

Schemas: https://fal.ai/api/openapi/queue/openapi.json?endpoint_id=<endpoint>
Reviewed 2026-10-30. Prices are deployment configuration, not provider guesses.
"""
import math

RATIOS = ["1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"]
MODELS = {
    "qwen-image": dict(title="Qwen Image", text="fal-ai/qwen-image", edit="fal-ai/qwen-image-edit", sizes=["720p", "1080p"], family="qwen"),
    "gpt-image-2-5-s": dict(title="GPT Image 2.5 Sunburst", text="openai/gpt-image-2.5/sunburst/text-to-image", edit="openai/gpt-image-2.5/sunburst/edit", sizes=["1K", "2K"], family="gpt"),
    "gpt-image-2-5-f": dict(title="GPT Image 2.5 Flare", text="openai/gpt-image-2.5/flare/text-to-image", edit="openai/gpt-image-2.5/flare/edit", sizes=["1K", "2K"], family="gpt"),
    "gpt-image-2": dict(title="GPT Image 2", text="openai/gpt-image-2", edit="openai/gpt-image-2/edit", sizes=["1K", "2K"], family="gpt"),
    "nano-banana": dict(title="Nano Banana", text="fal-ai/nano-banana", edit="fal-ai/nano-banana/edit", sizes=["1K"], family="nano"),
    "seedream-5-pro": dict(title="Seedream 5 Pro", text="bytedance/seedream/v5/pro/text-to-image", edit="bytedance/seedream/v5/pro/edit", sizes=["1K", "2K"], family="seedream_pro"),
    "seedream-5-lite": dict(title="Seedream 5 Lite", text="bytedance/seedream/v5/lite/text-to-image", edit="bytedance/seedream/v5/lite/edit", sizes=["2K"], family="seedream_lite"),
    "seedream-4-5": dict(title="Seedream 4.5", text="fal-ai/bytedance/seedream/v4.5/text-to-image", edit="fal-ai/bytedance/seedream/v4.5/edit", sizes=["2K"], family="seedream_45"),
    "kling-3-omni": dict(title="Kling O3", family="kling", sizes=["720p", "1080p"]),
    "pixverse-v6": dict(title="PixVerse V6", family="pixverse", sizes=["720p", "1080p"]),
}
PROVIDER_MODELS = {"fal:" + name for name in MODELS}
LAUNCH_MODELS = PROVIDER_MODELS - {"fal:nano-banana"}


def variants():
    for name, model in MODELS.items():
        video = model["family"] in ("kling", "pixverse")
        for size in model["sizes"]:
            for sound in ([False, True] if video else [None]):
                suffix = ("-audio" if sound else "-silent") if video else ""
                slug = f"{name}-{size.lower()}{suffix}"
                cost_env = "FAL_" + slug.replace("-", "_").upper() + ("_COST_PAISE_PER_SECOND" if video else "_COST_PAISE")
                config = {"provider": "fal", "app_model_id": name, "aspect_ratios": RATIOS if not video else ["16:9", "9:16", "1:1"], "input_modes": ["text", "image_and_text"]}
                if video:
                    config.update(resolution=size, durations=[5, 10] if name == "kling-3-omni" else [5, 10, 15], generated_audio=sound, image_aspect_ratio="source")
                else:
                    config.update(image_size=size, output_count=1)
                    if model["family"] == "gpt":
                        config["quality"] = "high"
                yield slug, model, config, cost_env


def dimensions(size, ratio):
    # Area-based sizes, rounded to 16px for GPT; satisfy Seedream's native bounds.
    edge = {"720p": 720, "1080p": 1080, "1K": 1024, "2K": 2048}[size]
    width, height = map(int, ratio.split(":"))
    return {"width": round(edge * math.sqrt(width / height) / 16) * 16,
            "height": round(edge * math.sqrt(height / width) / 16) * 16}


def request(payload, source_url=None):
    name = payload["model"].removeprefix("fal:")
    model = MODELS[name]
    config = payload["config"]
    size = config.get("image_size", config.get("resolution"))
    ratio = payload["aspect_ratio"]
    if size not in model["sizes"] or ratio not in config["aspect_ratios"]:
        raise ValueError("Unsupported fal settings")
    family = model["family"]
    body = {"prompt": payload["prompt"]}
    if family in ("kling", "pixverse"):
        duration, sound = payload["units"], payload["generated_audio"]
        if duration not in config["durations"] or sound is not config["generated_audio"]:
            raise ValueError("Video settings differ from the priced variant")
        mode = "image-to-video" if source_url else "text-to-video"
        if family == "kling":
            tier = "standard" if size == "720p" else "pro"
            endpoint = f"fal-ai/kling-video/o3/{tier}/{mode}"
            body.update(duration=str(duration), generate_audio=sound)
        else:
            endpoint = f"fal-ai/pixverse/v6/{mode}"
            body.update(duration=duration, resolution=size, generate_audio_switch=sound, generate_multi_clip_switch=False)
        body["enable_safety_checker"] = True
        # Both providers derive I2V geometry from the input image, not a ratio parameter.
        if source_url:
            body["image_url"] = source_url
        else:
            body["aspect_ratio"] = ratio
        return endpoint, body
    endpoint = model["edit"] if source_url else model["text"]
    body.update(num_images=1, sync_mode=False)
    if source_url:
        body["image_url" if family == "qwen" else "image_urls"] = source_url if family == "qwen" else [source_url]
    if family == "nano":
        body.update(aspect_ratio=ratio, output_format="png", safety_tolerance="4", limit_generations=True)
    else:
        body.update(image_size=dimensions(size, ratio), enable_safety_checker=True)
        if family != "seedream_45":
            body["output_format"] = "png"
        if family == "gpt":
            body["quality"] = "high"
        if family in ("seedream_lite", "seedream_45"):
            body["max_images"] = 1
        if family == "seedream_lite":
            body.update(sequential_image_generation="disabled", return_byteplus_urls=False)
    return endpoint, body
