' MiniDB GUI launcher - no console window.
' Double-click this file to open the MiniDB cmd-style window.
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

' Run from this script's folder so the "out" classpath resolves.
dir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = dir

' Show a message box (no console) if the project has not been compiled yet.
If Not fso.FileExists(dir & "\out\cli\Main.class") Then
    MsgBox "Compiled classes not found (out\cli\Main.class)." & vbCrLf & _
           "Please run build.bat first.", 48, "MiniDB"
    WScript.Quit 1
End If

' javaw = JVM with no console window; window style 0 = hidden.
shell.Run "javaw -Dfile.encoding=UTF-8 -cp out cli.Main --gui", 0, False
