package Code_SyS.nas_server

/**
 * Нумерация одинаковых имён: `photo.jpg`, `photo (1).jpg`, `photo (2).jpg`…
 *
 * Одно правило на всё приложение: приём файла, загрузка из браузера в «Загрузки»,
 * создание папки и восстановление из корзины. Раньше цикл был скопирован
 * в трёх местах, и правка одной копии не меняла остальные.
 */
internal object FileNames {

    /**
     * Бесконечная последовательность имён, начиная с исходного.
     * Точка в начале (`.env`) расширением не считается, поэтому суффикс
     * встаёт в конец: `.env (1)`.
     */
    fun numbered(name: String): Sequence<String> = sequence {
        yield(name)

        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0
        val base = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""

        var index = 1
        while (true) {
            yield("$base ($index)$extension")
            index++
        }
    }
}
