; Inno Setup Script for Remote Mouse & File Control App
; Generated for packaging a single executable with downloads folder and auto-start on login

[Setup]
; Basic Application Information
AppName=Remote Control App
AppVersion=1.0.0
; Default installation directory: Program Files\Remote Control App
DefaultDirName={pf}\Remote Control App
; Name in Start Menu
DefaultGroupName=Remote Control App
; Shortcut creation page disabled (we'll handle shortcuts explicitly)
DisableProgramGroupPage=yes
; Output installer name
OutputBaseFilename=RemoteControl_Setup
; Use LZMA compression for smaller installer
Compression=lzma
SolidCompression=yes
; Require administrator privileges to install to Program Files
PrivilegesRequired=admin

[Dirs]
; Ensure the 'downloads' folder exists in the install directory
Name: "{app}\downloads"; Flags: uninsalwaysuninstall

[Files]
; Include the single compiled executable
Source: "C:\Users\hp\PycharmProjects\PythonProject\dist\server.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; Desktop shortcut
Name: "{userdesktop}\Remote Control App"; Filename: "{app}\server.exe"; WorkingDir: "{app}"
; Startup folder shortcut to run on Windows login
Name: "{userstartup}\Remote Control App"; Filename: "{app}\server.exe"; WorkingDir: "{app}"

[Run]
; Optionally launch the app after installation
Filename: "{app}\server.exe"; Description: "Launch Remote Control App"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Remove the downloads folder on uninstall
Type: filesandordirs; Name: "{app}\downloads"
