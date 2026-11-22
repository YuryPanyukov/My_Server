package Code_SyS.nas_server

import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Прозрачное окно, которое показывает системный диалог
 * «Разрешить LanDrop удалить фото?». Само ничего не рисует: сразу после
 * результата закрывается.
 *
 * Запускается из уведомления или карточки на главном экране, когда браузер
 * попросил удалить медиа, а Android требует подтверждения пользователя.
 */
class DeleteConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // При отказе запрос остаётся: пользователь может попробовать снова.
        if (result.resultCode == RESULT_OK) {
            PendingDeletes.clear(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PendingDeletes.clear(this)
            finish()
            return
        }

        val uris = PendingDeletes.list(this)
        if (uris.isEmpty()) {
            finish()
            return
        }

        try {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (_: Exception) {
            // Медиа уже нет или система не дала диалог — запрос не нужен.
            PendingDeletes.clear(this)
            finish()
        }
    }
}
