from django.urls import path
from studio import views as v
from studio import owner
from studio import showcase
from studio import owner_credits

urlpatterns = [
    path("owner/", owner.dashboard, name="owner-dashboard"),
    path("owner/credits/", owner_credits.grant_credits, name="owner-credit-grant"),
    path("owner/credits/<uuid:identifier>/", owner_credits.receipt, name="owner-credit-receipt"),
    path("owner/showcase/", showcase.owner_templates, name="owner-templates"),
    path("owner/showcase/banners/", showcase.owner_banners, name="owner-banners"),
    path("owner/showcase/banners/<uuid:identifier>/", showcase.owner_banners, name="owner-banner-edit"),
    path("owner/showcase/<uuid:identifier>/", showcase.owner_templates, name="owner-template-edit"),
    path("owner/showcase/<uuid:identifier>/unpublish/", showcase.unpublish_template, name="owner-template-unpublish"),
    path("owner/login/", owner.sign_in, name="owner-login"),
    path("owner/logout/", owner.sign_out, name="owner-logout"),
    path("owner/records/<str:kind>/", owner.records, name="owner-records"),
    path("owner/jobs/<uuid:identifier>/", owner.job_detail, name="owner-job"),
    path("owner/add/<str:kind>/", owner.add_entry, name="owner-add"),
    path("owner/reverse/<uuid:identifier>/", owner.reverse_entry, name="owner-reverse"),
    path("owner/export/<str:kind>/", owner.export, name="owner-export"),
    path("health", v.Health.as_view()), path("ready", v.Ready.as_view()),
    path("auth/motion-ai/config", v.AuthConfig.as_view()),
    path("auth/motion-ai/google/native", v.SignIn.as_view()),
    path("auth/motion-ai/refresh", v.Refresh.as_view()),
    path("auth/motion-ai/revoke", v.RevokeRefresh.as_view()),
    path("auth/motion-ai/me", v.Me.as_view()), path("auth/motion-ai/logout", v.Logout.as_view()),
    path("api/v1/capabilities", v.Capabilities.as_view()),
    path("api/v1/templates", showcase.ShowcaseFeed.as_view()),
    path("api/v1/templates/artwork/<str:filename>", showcase.bundled_artwork, name="template-artwork"),
    path("api/v1/wallet", v.WalletView.as_view()), path("api/v1/transactions", v.Transactions.as_view()),
    path("api/v1/quotes", v.Quotes.as_view()), path("api/v1/jobs", v.Jobs.as_view()), path("api/v1/jobs/<uuid:identifier>", v.JobDetail.as_view()),
    path("api/v1/assets", v.Assets.as_view()), path("api/v1/assets/<uuid:identifier>", v.AssetDetail.as_view()),
    path("api/v1/assets/<uuid:identifier>/thumbnail", v.AssetThumbnail.as_view()),
    path("api/v1/billing/products", v.Products.as_view()), path("api/v1/billing/verify", v.VerifyPurchase.as_view()), path("api/v1/billing/purchases", v.Purchases.as_view()),
    path("api/v1/billing/entitlement", v.Entitlement.as_view()), path("api/v1/billing/subscriptions/verify", v.VerifySubscription.as_view()),
    path("api/v1/billing/play-notifications", v.PlayNotifications.as_view()),
]
