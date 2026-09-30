; AutoHotkey v2 (https://www.autohotkey.com): Ctrl+Alt+V — вставить из буфера OneTouch на рабочий стол.
; Положите onetouch.exe рядом со скриптом (или поправьте путь).
#Requires AutoHotkey v2.0
exe := A_ScriptDir "\onetouch.exe"
^!v:: {
    RunWait(A_ComSpec ' /c ""' exe '" paste > "%TEMP%\onetouch-paste.txt" 2>&1"', , "Hide")
    TrayTip(FileRead(A_Temp "\onetouch-paste.txt", "UTF-8"), "OneTouch")
}
