' MiniDB GUI launcher - auto-compile then launch, no console window.
' Double-click to compile (build.bat) and open the MiniDB GUI.
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

' Run from this script's folder so "out" and "src" resolve correctly.
base = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = base

' 1) Compile: run build.bat hidden (window style 0) and wait for it to finish.
shell.Run "cmd /c cd /d """ & base & """ && build.bat", 0, True

' 2) If compile failed (no Main.class), show the error and stop.
If Not fso.FileExists(base & "\out\cli\Main.class") Then
    MsgBox "Compile failed - cannot launch MiniDB." & vbCrLf & _
           "Run build.bat in a console to see the error.", 48, "MiniDB"
    WScript.Quit 1
End If

' 3) Launch the GUI. javaw = no console window; window style 0 = hidden.
shell.Run "javaw -Dfile.encoding=UTF-8 -cp out cli.Main --gui", 0, False
