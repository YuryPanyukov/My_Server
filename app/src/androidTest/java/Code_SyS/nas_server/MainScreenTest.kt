package Code_SyS.nas_server

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Главный экран на устройстве: сторож против «съехавшей» раскладки.
 *
 * Один раз карточки главного экрана перестали рисоваться: тумблер сервера,
 * адрес и QR-код пропали с экрана, поэтому сервер было нечем включить. Здесь
 * проверяется, что они действительно видны.
 *
 * Тест требует рабочего Wi-Fi: адрес и QR-код показываются только при наличии
 * IP-адреса телефона в сети.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun serverSwitchQrAndPageButtonsAreVisible() {
        rule.onNodeWithText("Файлы по Wi-Fi").assertIsDisplayed()

        // Тумблер сервера: находится по соседнему заголовку карточки, потому что
        // на экране есть и другие переключатели.
        rule.onNode(isToggleable() and hasAnySibling(hasText("Сервер", substring = true)))
            .assertIsDisplayed()
        rule.onNodeWithText("Сервер", substring = true).assertIsDisplayed()

        // Адрес и QR-код для второго телефона.
        rule.onNodeWithText("АДРЕС В БРАУЗЕРЕ").assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.qr_code_description))
            .assertIsDisplayed()

        // Кнопки страниц: «Открыть страницу» видна, галерея и видеотека — ниже
        // по списку, поэтому проверяем их наличие в разметке.
        rule.onNodeWithText("Открыть страницу").assertIsDisplayed()
        rule.onNodeWithText("Галерея").assertExists()
        rule.onNodeWithText("Видеотека").assertExists()
    }

    @Test
    fun serverSwitchStartsAndStopsTheServer() {
        val toggle = { rule.onNode(isToggleable() and hasAnySibling(hasText("Сервер", substring = true))) }

        toggle().performClick()
        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodesWithText("Сервер включён").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Сервер включён").assertIsDisplayed()

        toggle().performClick()
        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodesWithText("Сервер выключен").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Сервер выключен").assertIsDisplayed()
    }
}
