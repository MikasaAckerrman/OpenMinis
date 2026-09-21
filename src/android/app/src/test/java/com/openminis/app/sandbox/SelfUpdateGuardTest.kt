import com.openminis.app.sandbox.SelfUpdateCommandAnalysis

/**
 * Локальные тесты анализа команд само-обновления: чистая логика без
 * Android, гоняется kotlinc'ом в песочнице (правило проекта — доказывать
 * локально, сборка только подтверждает уже доказанное). Сценарии взяты из
 * реального инцидента 2026-09-21 13:12 и из поведения агентов, которые
 * грепают этот репозиторий (ложные срабатывания недопустимы — они
 * вытаскивают приложение на foreground alarm'ом).
 */
fun main() {
    var ok = 0
    var fail = 0

    fun check(expect: Boolean, command: String, note: String = "") {
        val got = SelfUpdateCommandAnalysis.isPackageInstall(command)
        if (got == expect) {
            ok++
        } else {
            fail++
            println("ПРОВАЛ  ждали $expect, получили $got")
            println("        команда: $command")
            if (note.isNotEmpty()) println("        примечание: $note")
        }
    }

    fun checkPaths(expect: List<String>, command: String) {
        val got = SelfUpdateCommandAnalysis.extractApkPaths(command)
        if (got == expect) {
            ok++
        } else {
            fail++
            println("ПРОВАЛ путей  ждали $expect, получили $got")
            println("        команда: $command")
        }
    }

    // ---- ПРЯМЫЕ УСТАНОВКИ (инцидент 13:12 и его варианты) ----
    check(true, "pm install -r -t /data/local/tmp/minis-test.apk", "точная команда инцидента")
    check(true, "pm install /data/local/tmp/x.apk")
    check(true, "android-shizuku-cli exec \"pm install -r -t /data/local/tmp/minis-test.apk\"", "как реально запускал агент")
    check(true, "sh -c 'pm install -r /sdcard/Download/app.apk'")
    check(true, "cmd package install /data/local/tmp/y.apk")
    check(true, "pm install-create -r", "шаг 1 многосессионной установки")
    check(true, "pm install-write -S 39695965 3 /data/local/tmp/minis-test.apk", "шаг 2")
    check(true, "pm install-commit 3", "шаг 3")
    check(true, "ADB=1 pm install -t /data/local/tmp/z.apk", "env-префикс")
    check(true, "timeout 300 pm install -t /data/local/tmp/z.apk", "обёртка timeout")
    check(true, "cd /tmp && pm install ./build.apk", "cd-префикс")
    check(true, "cat note.txt | pm install -t /data/local/tmp/a.apk", "уступка пайпа реальному install")
    check(true, "su -c 'pm install -r /data/local/tmp/b.apk'")
    check(true, "PM_INSTALL=1 pm install x.apk", "env-префикс с похожим именем")
    check(true, "android-shizuku-cli package install /data/local/tmp/minis-test.apk", "CLI-установка через рут (убивает процесс)")
    check(true, "android-shizuku-cli package install /data/local/tmp/x.apk --grant-permissions", "CLI-установка с флагами")

    // ---- ЧТЕНИЕ, НЕ ВЫПОЛНЕНИЕ (ложные срабатывания недопустимы) ----
    check(false, "grep -rn install-create src/android/", "агент грепает этот самый файл")
    check(false, "grep -rn 'pm install' src/")
    check(false, "cat install_log.txt | grep install-commit")
    check(false, "echo 'install-create -r' > /tmp/script.sh", "запись скрипта, не запуск")
    check(false, "sed 's/install-write/install-read/g' SelfUpdateGuard.kt")
    check(false, "sh -c 'grep install-create src/android/'", "grep внутри sh -c")
    check(false, "ls -la /data/local/tmp/*.apk")
    check(false, "find / -name '*.apk' | head -5")
    check(false, "stat /data/local/tmp/minis-test.apk")
    check(false, "sha256sum OpenMinis-aeade5d-legacy-mechanics.apk", "просто чтение APK-файла")
    check(false, "strings base.apk | grep install-commit")
    check(false, "diff <(grep install-create a) <(grep install-create b)")
    check(false, "awk '/install-write/ {print}' log.txt")
    check(false, "echo pm install -t x.apk", "echo текста установки — не запуск")
    check(false, "tail -100 minis-2026-09-21.log")

    // ---- ПРОЖИТЫЕ ЛОЖНЫ-ТРИГГЕРЫ (инцидент «вылетает много раз» 16:12—16:13) ----
    check(false, "android-shizuku-cli exec \"grep -cE 'KILLED|relaunch|alarm' /data/data/com.openminis.app.clone/files/logs/minis-2026-09-21.log\"", "grep с | внутри кавычек — НЕ пайп")
    check(false, "android-shizuku-cli exec \"grep -E 'KILLED|relaunch|alarm|self-update' /data/data/com.openminis.app.clone/files/logs/minis-2026-09-21.log\"", "кавычки защищают паттерн")
    check(false, "grep 'package-install detected' /data/data/com.openminis.app.clone/files/logs/minis-2026-09-21.log", "grep по логу guard'а")
    check(false, "# Установка vc51 (мост работает изнутри пески — но ksu-коннект режется SELinux; ставлю через ФАЙЛОВЫЙ мост): cp /tmp/apk51_out/app-clone.apk /tmp/apk51-copy.apk", "комментарий-аннотация агента")
    check(false, "cp /tmp/apk51_out/app-clone.apk /tmp/apk51-copy.apk", "копия файла — не установка")
    check(false, "cp /tmp/serve/test.apk /data/local/tmp/test.apk && ls -la /data/local/tmp/test.apk", "копия в tmp — не установка")

    // ---- ПУТИ ----
    checkPaths(listOf("/data/local/tmp/minis-test.apk"), "pm install -r -t /data/local/tmp/minis-test.apk")
    checkPaths(listOf("/sdcard/D/a.apk", "/tmp/b.APK"), "pm install /sdcard/D/a.apk && pm install /tmp/b.APK")
    checkPaths(listOf(), "pm install-create -r")

    println(if (fail == 0) "OK: $ok проверок, 0 провалов" else "ПРОВАЛОВ: $fail из ${ok + fail}")
    if (fail > 0) kotlin.system.exitProcess(1)
}
