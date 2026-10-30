import os

from django.conf import settings
from django.contrib.auth import get_user_model
from django.contrib.auth.password_validation import CommonPasswordValidator, NumericPasswordValidator
from django.core.exceptions import ValidationError
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from studio.models import OwnerAudit


class Command(BaseCommand):
    help = "Create the configured private owner from server environment, without interactive shell access."

    @transaction.atomic
    def handle(self, *args, **options):
        username = settings.OWNER_USERNAME
        password = os.environ.get('MOTION_AI_OWNER_BOOTSTRAP_PASSWORD', '')
        if not username or not password:
            self.stdout.write('Owner bootstrap skipped: username or bootstrap password not configured.')
            return
        User = get_user_model()
        existing = User.objects.filter(username=username).first()
        if existing:
            if not existing.is_superuser or not existing.is_active:
                raise CommandError('Configured username already exists without active owner privileges; no changes made.')
            self.stdout.write('Owner already exists; password and permissions unchanged.')
            return
        try:
            User._meta.get_field('username').clean(username, None)
            if len(password) < 16 or len(password) > 256:
                raise ValidationError('Invalid password length')
            CommonPasswordValidator().validate(password)
            NumericPasswordValidator().validate(password)
            if username.casefold() in password.casefold():
                raise ValidationError('Password contains username')
        except ValidationError:
            raise CommandError('Use a valid username and a unique 16–256 character password, not numeric or containing the username.') from None
        owner = User.objects.create_superuser(username=username, password=password)
        OwnerAudit.objects.create(owner=owner, action='bootstrap_owner')
        self.stdout.write('Private owner created. Remove MOTION_AI_OWNER_BOOTSTRAP_PASSWORD from the service environment.')
