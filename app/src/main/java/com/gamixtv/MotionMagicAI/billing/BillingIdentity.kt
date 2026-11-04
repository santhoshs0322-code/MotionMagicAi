package com.gamixtv.MotionMagicAI.billing

import java.security.MessageDigest

/** Matches Django authentication.digest(str(account.pk)); not a credential. */
fun playAccountId(accountId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
