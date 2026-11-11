; Gaz Live Mixer - Windows installer (Inno Setup 6.3+)
; Gaz Media Production - Everything Multimedia
;
; Build:  iscc /DPluginDir=<plugin out> /DAppDir=<desktop app bin> /DWebView2Setup=<MicrosoftEdgeWebview2Setup.exe> GazLiveMixer.iss

#define MyAppName "Gaz Live Mixer"
#ifndef MyAppVersion
  #define MyAppVersion "1.0.0"
#endif
#define MyPublisher "Gaz Media Production"
#define MyURL "https://github.com/GAZMEDIA/gaz-live-mixer"
#ifndef PluginDir
  #define PluginDir "..\out\gaz-live-mixer"
#endif
#ifndef AppDir
  #define AppDir "..\desktop-app\bin\Release\net48"
#endif

[Setup]
AppId={{D3CE7575-1D04-4234-A0CB-18D2AF2B23C0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyPublisher}
AppPublisherURL={#MyURL}
AppSupportURL={#MyURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=gaz.ico
UninstallDisplayIcon={app}\GazLiveMixer.exe
UninstallDisplayName={#MyAppName}
OutputDir=output
OutputBaseFilename=GazLiveMixer-Setup-{#MyAppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
VersionInfoVersion={#MyAppVersion}
VersionInfoCompany={#MyPublisher}
VersionInfoDescription={#MyAppName} Setup

[Types]
Name: "full"; Description: "Full install (OBS plugin + desktop app)"
Name: "obspc"; Description: "OBS PC only (plugin)"
Name: "remote"; Description: "Remote PC only (desktop app)"
Name: "custom"; Description: "Custom"; Flags: iscustom

[Components]
Name: "plugin"; Description: "OBS plugin – puts the mixer inside OBS (install on the PC that runs OBS)"; Types: full obspc
Name: "app"; Description: "Gaz Live Mixer desktop app – control OBS from this or another PC"; Types: full remote

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; Components: app
Name: "firewall"; Description: "Allow phones and other PCs on my network to connect (Windows Firewall)"

[Files]
; OBS plugin (goes into the OBS Studio folder chosen on the "OBS Studio folder" page)
Source: "{#PluginDir}\obs-plugins\64bit\gaz-live-mixer.dll"; DestDir: "{code:GetOBSDir}\obs-plugins\64bit"; Components: plugin; Flags: ignoreversion
Source: "{#PluginDir}\data\obs-plugins\gaz-live-mixer\*"; DestDir: "{code:GetOBSDir}\data\obs-plugins\gaz-live-mixer"; Components: plugin; Flags: ignoreversion recursesubdirs createallsubdirs
; Desktop app
Source: "{#AppDir}\*"; DestDir: "{app}"; Components: app; Excludes: "*.pdb,*.xml"; Flags: ignoreversion recursesubdirs createallsubdirs
#ifdef WebView2Setup
Source: "{#WebView2Setup}"; DestDir: "{tmp}"; DestName: "MicrosoftEdgeWebview2Setup.exe"; Components: app; Flags: deleteafterinstall; Check: NeedsWebView2
#endif

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\GazLiveMixer.exe"; Components: app
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\GazLiveMixer.exe"; Components: app; Tasks: desktopicon

[Run]
#ifdef WebView2Setup
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft Edge WebView2 Runtime…"; Components: app; Check: NeedsWebView2; Flags: waituntilterminated
#endif
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer panel (TCP 4466)"""; Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Gaz Live Mixer panel (TCP 4466)"" dir=in action=allow protocol=TCP localport=4466 profile=private,domain"; Flags: runhidden; Tasks: firewall; StatusMsg: "Opening the firewall for the mixer…"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer discovery (UDP 4467)"""; Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Gaz Live Mixer discovery (UDP 4467)"" dir=in action=allow protocol=UDP localport=4467 profile=private,domain"; Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer app"""; Flags: runhidden; Tasks: firewall; Components: app
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Gaz Live Mixer app"" dir=in action=allow program=""{app}\GazLiveMixer.exe"" profile=private,domain"; Flags: runhidden; Tasks: firewall; Components: app
Filename: "{app}\GazLiveMixer.exe"; Description: "Open Gaz Live Mixer now"; Components: app; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer panel (TCP 4466)"""; Flags: runhidden; RunOnceId: "fw1"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer discovery (UDP 4467)"""; Flags: runhidden; RunOnceId: "fw2"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Gaz Live Mixer app"""; Flags: runhidden; RunOnceId: "fw3"

[Messages]
WelcomeLabel2=This will install [name/ver] on your computer.%n%nGaz Live Mixer lets you control OBS Studio like a video mixer from your phone, a tablet or another PC.%n%nPlease close OBS Studio before continuing.

[Code]
var
  OBSPage: TInputDirWizardPage;

function DefaultOBSDir(): String;
var
  P: String;
begin
  if RegQueryStringValue(HKLM64, 'SOFTWARE\OBS Studio', '', P) and (P <> '') then
    Result := P
  else if RegQueryStringValue(HKLM32, 'SOFTWARE\OBS Studio', '', P) and (P <> '') then
    Result := P
  else
    Result := ExpandConstant('{commonpf64}\obs-studio');
end;

function GetOBSDir(Param: String): String;
begin
  Result := RemoveBackslashUnlessRoot(OBSPage.Values[0]);
end;

function OBSRunning(): Boolean;
var
  Code: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/C tasklist /FI "IMAGENAME eq obs64.exe" /NH | find /I "obs64.exe" >nul',
                 '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0);
end;

function NeedsWebView2(): Boolean;
var
  V: String;
begin
  Result := not (
    (RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', V) and (V <> '') and (V <> '0.0.0.0')) or
    (RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', V) and (V <> '') and (V <> '0.0.0.0')));
end;

procedure InitializeWizard();
begin
  OBSPage := CreateInputDirPage(wpSelectTasks,
    'OBS Studio folder', 'Where is OBS Studio installed?',
    'The Gaz Live Mixer plugin will be added to this OBS Studio folder. ' +
    'It normally is C:\Program Files\obs-studio.', False, '');
  OBSPage.Add('');
  OBSPage.Values[0] := DefaultOBSDir();
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := (PageID = OBSPage.ID) and not WizardIsComponentSelected('plugin');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = OBSPage.ID then
  begin
    if not FileExists(AddBackslash(OBSPage.Values[0]) + 'bin\64bit\obs64.exe') then
      Result := MsgBox('OBS Studio was not found in this folder:' + #13#10 + OBSPage.Values[0] + #13#10#13#10 +
                       'Install the plugin here anyway?', mbConfirmation, MB_YESNO) = IDYES;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if WizardIsComponentSelected('plugin') then
    while OBSRunning() do
    begin
      if MsgBox('OBS Studio is open. Please close OBS Studio, then click OK.' + #13#10#13#10 +
                'Click Cancel to stop the installation.', mbInformation, MB_OKCANCEL) = IDCANCEL then
      begin
        Result := 'Close OBS Studio and run the installer again.';
        Exit;
      end;
    end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssDone) and WizardIsComponentSelected('plugin') and not WizardSilent() then
    MsgBox('The OBS plugin is installed.' + #13#10#13#10 +
           'Start OBS Studio and open Docks > Gaz Live Mixer to see the QR code for your phone.',
           mbInformation, MB_OK);
end;
