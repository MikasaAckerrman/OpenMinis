import com.openminis.app.sandbox.DestructiveCommandPolicy
import com.openminis.app.sandbox.DestructiveCommandPolicy.Verdict

/**
 * Локальные тесты классификатора: чистая логика без Android, гоняется
 * kotlinc'ом в песочнице. Правило проекта — доказывать локально, сборка
 * только подтверждает уже доказанное.
 */
fun main() {
    var ok = 0
    var fail = 0

    fun check(expect: Verdict, command: String, note: String = "") {
        val d = DestructiveCommandPolicy.classify(command)
        if (d.verdict == expect) {
            ok++
        } else {
            fail++
            println("ПРОВАЛ  ждали $expect, получили ${d.verdict}")
            println("        команда: $command")
            println("        причина движка: ${d.reason}")
            if (note.isNotEmpty()) println("        смысл теста: $note")
        }
    }

    println("=== REFUSE: данные пользователя и системные корни ===")
    check(Verdict.REFUSE, "rm -rf /var/minis/shared")
    check(Verdict.REFUSE, "rm -rf /var/minis/shared/slayer3d")
    check(Verdict.REFUSE, "rm -rf /var/minis/memory")
    check(Verdict.REFUSE, "rm /var/minis/memory/2026-08-14.md",
        "память — без уступок даже для одного файла")
    check(Verdict.REFUSE, "rm -rf /var/minis/skills/review")
    check(Verdict.REFUSE, "rm -rf /var/minis/mounts/xash")
    check(Verdict.REFUSE, "rm -rf /")
    check(Verdict.REFUSE, "rm -rf /usr")
    check(Verdict.REFUSE, "rm -rf /etc")
    check(Verdict.REFUSE, "sudo rm -rf /var/minis")
    check(Verdict.REFUSE, "FOO=1 rm -rf /var/minis/shared",
        "env-присваивание перед командой не должно скрывать rm")
    check(Verdict.REFUSE, "cd /tmp && rm -rf /var/minis/shared",
        "строгий вердикт побеждает в цепочке команд")

    println("=== REFUSE: один файл в shared — уступка есть, но не для каталога ===")
    check(Verdict.ALLOW, "rm /var/minis/shared/toolbox/scratch.tmp",
        "свой временный файл в проектах удалить можно")
    check(Verdict.REFUSE, "rm -rf /var/minis/shared/toolbox/cli",
        "каталог в проектах — нельзя")
    check(Verdict.REFUSE, "rm /var/minis/shared/a.txt /var/minis/shared/b.txt",
        "два файла — уже не уступка")

    println("=== CONFIRM: та самая авария и близкие формы ===")
    check(Verdict.CONFIRM, "rm -rf om*", "маска: причина инцидента 14.08")
    check(Verdict.CONFIRM, "cd /tmp && rm -rf om*")
    check(Verdict.CONFIRM, "rm -rf /tmp/build /tmp/dist", "несколько путей рекурсивно")
    check(Verdict.CONFIRM, "rm -rf /tmp/omw", "известный рабочий клон")
    check(Verdict.CONFIRM, "rm -rf /tmp/om")
    check(Verdict.CONFIRM, "rm -rf /tmp/project/.git", "каталог .git")
    check(Verdict.CONFIRM, "rm *.log", "маска без рекурсии")
    check(Verdict.CONFIRM, "rm -f *.tmp")
    check(Verdict.CONFIRM, "find /tmp -name '*.o' -delete")
    check(Verdict.CONFIRM, "git clean -fd")
    check(Verdict.CONFIRM, "shred -u /tmp/secret /tmp/other",
        "shred тоже разрушающий")

    println("=== ALLOW: обычная работа не должна спрашивать ===")
    check(Verdict.ALLOW, "rm /tmp/scratch.txt")
    check(Verdict.ALLOW, "rm -f /tmp/nope")
    check(Verdict.ALLOW, "rm -rf /tmp/build", "свой каталог сборки — обычное дело")
    check(Verdict.ALLOW, "rm -rf node_modules")
    check(Verdict.ALLOW, "rm /tmp/a.txt /tmp/b.txt /tmp/c.txt",
        "несколько файлов без -r")
    check(Verdict.ALLOW, "ls -la /var/minis/shared", "чтение не трогаем")
    check(Verdict.ALLOW, "cat /var/minis/memory/GLOBAL.md")
    check(Verdict.ALLOW, "git status")
    check(Verdict.ALLOW, "git clean -n", "сухой прогон без -f")
    check(Verdict.ALLOW, "mkdir -p /tmp/x && echo hi > /tmp/x/f")
    check(Verdict.ALLOW, "npm install")
    check(Verdict.ALLOW, "echo 'rm -rf /' > /tmp/note.txt",
        "строка в кавычках — не команда")
    check(Verdict.ALLOW, "find /tmp -name '*.log'", "find без -delete")

    println()
    println("итог: $ok ок, $fail провал")
    if (fail > 0) throw AssertionError("$fail тестов провалено")
}
