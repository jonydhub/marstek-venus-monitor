; Inno Setup 6 script — per-user install, no admin rights.
; Build: iscc /DAppVersion=1.3.5 windows\packaging\installer.iss  (after the PyInstaller onedir build)
#ifndef AppVersion
  #define AppVersion "1.3.5"
#endif
#define AppName "Marstek Venus Monitor"
#define AppExe "MarstekVenusMonitor.exe"

[Setup]
AppId={{6C1F0B7E-2E52-4B7B-9D7B-3B5C2B1E8A11}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Marstek Venus Monitor contributors
AppPublisherURL=https://github.com/jonydhub/marstek-venus-monitor
AppSupportURL=https://github.com/jonydhub/marstek-venus-monitor/issues
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=MarstekVenusMonitor-{#AppVersion}-setup
SetupIconFile=..\assets\app.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
AppMutex=Local\MarstekVenusMonitor.SingleInstance
CloseApplications=yes
LicenseFile=..\..\LICENSE

[Languages]
Name: "uk"; MessagesFile: "compiler:Languages\Ukrainian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "Запускати з Windows (у треї)"; Flags: unchecked

[Files]
Source: "..\dist\MarstekVenusMonitor\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; Same value the tray menu «Запускати з Windows» toggles; always removed on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "MarstekVenusMonitor"; ValueData: """{app}\{#AppExe}"" --autostart"; Tasks: autostart
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "MarstekVenusMonitor"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /T /IM {#AppExe}"; Flags: runhidden; RunOnceId: "StopApp"

; Settings (%APPDATA%\MarstekVenusMonitor) and history/logs (%LOCALAPPDATA%\MarstekVenusMonitor) are kept on
; uninstall so a reinstall keeps your configuration; delete those folders manually to remove all traces.
