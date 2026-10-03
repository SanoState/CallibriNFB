package com.callibri.nfb

import android.app.Application
import com.callibri.nfb.session.SessionPortal
import com.callibri.nfb.ui.NeurofeedbackSession

/**
 * One neurofeedback session for the process. The Activity and the foreground service
 * both call [session]; neither constructs a second [com.callibri.nfb.callibri.CallibriManager].
 */
class CallibriNfbApp : Application() {
    private val sessions = SessionPortal { NeurofeedbackSession(this) }

    fun session(): NeurofeedbackSession = sessions.get()
}
