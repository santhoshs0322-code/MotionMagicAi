from django.contrib import admin
from .models import Account, Rate, Product, Wallet, Ledger, Job, Purchase, Budget

@admin.register(Rate)
class RateAdmin(admin.ModelAdmin):
    list_display = ("slug", "kind", "enabled", "unit_cost_paise", "overhead_paise", "revision")
    def save_model(self, request, obj, form, change):
        if change:
            obj.revision += 1
        super().save_model(request, obj, form, change)

@admin.register(Product)
class ProductAdmin(admin.ModelAdmin):
    list_display = ("sku", "title", "credit_paise", "enabled")

class AuditAdmin(admin.ModelAdmin):
    def has_add_permission(self, request): return False
    def has_change_permission(self, request, obj=None): return False
    def has_delete_permission(self, request, obj=None): return False

for model in (Account, Wallet, Ledger, Job, Purchase, Budget):
    admin.site.register(model, AuditAdmin)
