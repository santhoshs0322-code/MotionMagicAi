from django.urls import path
from studio import views as v

urlpatterns = [
    path("health", v.Health.as_view()), path("ready", v.Ready.as_view()),
    path("auth/motion-ai/config", v.AuthConfig.as_view()),
    path("auth/motion-ai/google/native", v.SignIn.as_view()),
    path("auth/motion-ai/me", v.Me.as_view()), path("auth/motion-ai/logout", v.Logout.as_view()),
    path("api/v1/capabilities", v.Capabilities.as_view()),
    path("api/v1/wallet", v.WalletView.as_view()), path("api/v1/transactions", v.Transactions.as_view()),
    path("api/v1/quotes", v.Quotes.as_view()), path("api/v1/jobs", v.Jobs.as_view()), path("api/v1/jobs/<uuid:identifier>", v.JobDetail.as_view()),
    path("api/v1/assets", v.Assets.as_view()), path("api/v1/assets/<uuid:identifier>", v.AssetDetail.as_view()),
    path("api/v1/billing/products", v.Products.as_view()), path("api/v1/billing/verify", v.VerifyPurchase.as_view()), path("api/v1/billing/purchases", v.Purchases.as_view()),
]
