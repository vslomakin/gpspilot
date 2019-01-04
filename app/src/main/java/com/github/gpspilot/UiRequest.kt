package com.github.gpspilot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlin.reflect.KClass


sealed class UiRequest {

    /**
     * Toast abstraction to construct toasts outside of view.
     */
    data class Toast(@StringRes val text: Int, val length: Length) : UiRequest() {
        enum class Length(val value: Int) {
            SHORT(android.widget.Toast.LENGTH_SHORT),
            LONG(android.widget.Toast.LENGTH_LONG)
        }
    }

    /**
     * Dialog abstraction to construct dialogs outside of view.
     */
    data class Dialog(
        @StringRes val text: Int,
        @StringRes val positiveBtn: Int,
        @StringRes val negativeBtn: Int,
        val positiveCallback: (() -> Unit)? = null,
        val negativeCallback: (() -> Unit)? = null
    ) : UiRequest()

    data class StartActivity(
        val activity: KClass<out Activity>,
        val data: Bundle? = null,
        val requestCode: Int? = null
    ) : UiRequest()

    object FinishActivityRequest : UiRequest()


    data class StartActionMode(
        @StringRes val title: String? = null,
        val onClose: (() -> Unit)? = null
    ) : UiRequest()

    object StopActionMode : UiRequest()
}




fun Context.showToast(vo: UiRequest.Toast) {
    Toast.makeText(this, vo.text, vo.length.value).show()
}


fun UiRequest.Dialog.buildDialog(ctx: Context): AlertDialog {
    val builder = AlertDialog.Builder(ctx).apply {
        setMessage(text)
        setPositiveButton(positiveBtn, positiveCallback?.toDialogClickListener())
        setNegativeButton(negativeBtn, negativeCallback?.toDialogClickListener())
        setCancelable(false)
    }
    return builder.create()
}

private fun (() -> Unit).toDialogClickListener()
        = DialogInterface.OnClickListener { _, _ -> invoke() }


fun KClass<out Activity>.toRequest() = UiRequest.StartActivity(this)


private class CABController(private val activity: Activity) {
    private var actionMode: ActionMode? = null

    fun start(req: UiRequest.StartActionMode) {
        if (actionMode == null) {
            actionMode = activity.startActionMode(Callback(req))
        }
    }

    fun stop() { actionMode?.apply { finish() } }

    private inner class Callback(private val req: UiRequest.StartActionMode) : ActionMode.Callback {
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = true

        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = true

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            req.title?.let { mode.title = it }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            req.onClose?.invoke()
        }
    }
}


@UseExperimental(ObsoleteCoroutinesApi::class)
fun <T> T.handleUiRequests(vos: ReceiveChannel<UiRequest>) where T : Activity, T : CoroutineScope {
    val cabController = CABController(this)

    launch(Dispatchers.Main) {
        vos.consumeEach { when (it) {
            is UiRequest.Toast -> showToast(it)
            is UiRequest.Dialog -> it.buildDialog(this@handleUiRequests).show()
            is UiRequest.StartActivity -> start(it.activity, it.requestCode, it.data)
            is UiRequest.FinishActivityRequest -> finish()
            is UiRequest.StartActionMode -> cabController.start(it)
            is UiRequest.StopActionMode -> cabController.stop()
        } }
    }
}