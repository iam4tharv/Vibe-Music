package com.music.vibe.data.firebase

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.music.vibe.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages Firebase Authentication for Vibe Music.
 *
 * Provides background anonymous/guest authentication so the app is always
 * authenticated without blocking playback or demanding a sign-in dialog.
 * Also provides optional Google Sign-In via Credential Manager for linking
 * full accounts and syncing across devices.
 */
object FirebaseAuthManager {

    private const val TAG = "VibeMusic"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class UserAccount(
        val uid: String = "",
        val email: String? = null,
        val displayName: String? = null,
        val isAnonymous: Boolean = true,
        val isSignedIn: Boolean = false,
    )

    private val _currentUser = MutableStateFlow(UserAccount())
    val currentUser = _currentUser.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) {
            TrackLog.w(TAG, "FirebaseAuth is unavailable in this environment")
            return
        }

        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = if (user != null) {
                UserAccount(
                    uid = user.uid,
                    email = user.email,
                    displayName = user.displayName,
                    isAnonymous = user.isAnonymous,
                    isSignedIn = true,
                )
            } else {
                UserAccount()
            }
        }

        // Silent anonymous sign-in if no user session is present
        if (auth.currentUser == null) {
            scope.launch {
                runCatching {
                    auth.signInAnonymously().await()
                    TrackLog.d(TAG, "Firebase silent anonymous sign-in succeeded: ${auth.currentUser?.uid}")
                }.onFailure {
                    TrackLog.w(TAG, "Firebase anonymous sign-in failed: ${it.message}")
                }
            }
        } else {
            TrackLog.d(TAG, "Firebase user already signed in: ${auth.currentUser?.uid} (anonymous=${auth.currentUser?.isAnonymous})")
        }
    }

    suspend fun signInWithGoogle(context: Context, serverClientId: String? = null): Result<UserAccount> {
        val auth = FirebaseAuth.getInstance()
        return runCatching {
            val credentialManager = CredentialManager.create(context)
            val googleIdOptionBuilder = GetSignInWithGoogleOption.Builder(
                serverClientId ?: "1098359739443-dummy.apps.googleusercontent.com",
            )
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOptionBuilder.build())
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleIdTokenCredential.idToken

            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            val current = auth.currentUser
            val authResult = if (current != null && current.isAnonymous) {
                // Link guest account to retain all preferences/playlists
                current.linkWithCredential(firebaseCredential).await()
            } else {
                auth.signInWithCredential(firebaseCredential).await()
            }

            val user = authResult.user ?: error("No user returned from Firebase")
            UserAccount(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                isAnonymous = user.isAnonymous,
                isSignedIn = true,
            ).also { _currentUser.value = it }
        }.onFailure { error ->
            if (error !is GetCredentialCancellationException) {
                TrackLog.w(TAG, "Google Sign-In failed: ${error.message}")
            }
        }
    }

    fun signOut() {
        val auth = FirebaseAuth.getInstance()
        auth.signOut()
        scope.launch {
            runCatching { auth.signInAnonymously().await() }
        }
    }
}
