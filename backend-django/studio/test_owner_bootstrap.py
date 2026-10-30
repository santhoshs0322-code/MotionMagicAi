import io
import os
from unittest.mock import patch

from django.contrib.auth.models import User
from django.core.management import call_command, CommandError
from django.test import TestCase, override_settings

from .models import OwnerAudit


@override_settings(OWNER_USERNAME='motion-owner')
class OwnerBootstrapTests(TestCase):
    def run_bootstrap(self, password):
        output = io.StringIO()
        with patch.dict(os.environ, {'MOTION_AI_OWNER_BOOTSTRAP_PASSWORD': password}):
            call_command('bootstrap_owner', stdout=output)
        self.assertNotIn(password, output.getvalue()) if password else None
        return output.getvalue()

    def test_create_hashes_password_and_redeploy_preserves_login(self):
        password = 'Unique-test-secret-83!'
        self.run_bootstrap(password)
        user = User.objects.get(username='motion-owner')
        self.assertTrue(user.is_superuser and user.is_active)
        self.assertTrue(user.check_password(password))
        self.assertNotEqual(user.password, password)
        self.run_bootstrap('Different-test-secret-52!')
        user.refresh_from_db()
        self.assertTrue(user.check_password(password))
        self.assertEqual(OwnerAudit.objects.count(), 1)

    def test_absent_password_skips_and_weak_password_fails(self):
        self.run_bootstrap('')
        with self.assertRaises(CommandError): self.run_bootstrap('weak')
        self.assertFalse(User.objects.exists())

    def test_existing_regular_user_is_never_promoted(self):
        User.objects.create_user('motion-owner')
        with self.assertRaises(CommandError): self.run_bootstrap('Unique-test-secret-83!')
        self.assertFalse(User.objects.get().is_superuser)
