# Google Play ratings

The automatic native Google Play review request becomes eligible after the signed-in
account opens three distinct completed images or videos. Results opened in My Projects
and downloaded copies share one creation ID, so they cannot count twice. Device Voice,
unfinished/failed jobs, template samples and other accounts do not count.

The request runs only after returning Home and remaining there for one second, while
the activity is resumed, the keyboard is closed, and no template preview, work,
completion message, error, quote or purchase is open. Signing in or launching the app
alone never triggers it. It does not add work to login, creation or downloading.

Milestones are local and account-specific, stored as at most three IDs under a hashed
account key. A device-wide 90-day cooldown is persisted before requesting Google Play,
including when Google suppresses the card or the optional request fails. Signing out,
account changes, rotation and restarting the app cannot bypass this cooldown. Clearing
app data or reinstalling resets local state; Google also applies its own quota.

**Me → Rate Us** and **Settings → Rate Us** open the current app's Play Store listing,
with an HTTPS browser fallback if Play Store is absent. A successful launch of the
listing also starts the cooldown. No credits or privileges depend on leaving a review.
The native review card is displayed as supplied by Google, with no custom rating gate.

Build and upload the Android app to enable this. No backend changes, environment
variables or database migrations are required. Google does not tell the app whether
the dialog appeared, the user dismissed it, or a review was submitted.

Verification uses bounded-history/cooldown unit tests and Android tests with Google's
FakeReviewManager, isolated preferences and intercepted listing intents. It does not
submit real reviews or call a generation provider. To verify the real dialog, install
from a Google Play internal test track using an eligible tester account.

References:
- https://developer.android.com/guide/playcore/in-app-review
- https://developer.android.com/guide/playcore/in-app-review/kotlin-java
- https://developer.android.com/guide/playcore/in-app-review/test
