// Gaz Live Mixer for Windows - Gaz Media Production
// Finds the OBS PC running the Gaz Live Mixer plugin on the network and opens the mixer in its own window.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace GazLiveMixer
{
    internal sealed class Mixer
    {
        public string Name, Host;
        public int Port;
        public string Url => $"http://{Host}:{Port}/";
        public string Address => $"{Host}:{Port}";
    }

    internal sealed class MainForm : Form
    {
        const int DiscoveryPort = 4467;
        const int DefaultPort = 4466;

        static readonly Color Bg = Color.FromArgb(11, 13, 16);
        static readonly Color Panel1 = Color.FromArgb(20, 23, 28);
        static readonly Color Panel2 = Color.FromArgb(27, 31, 38);
        static readonly Color Red = Color.FromArgb(255, 59, 48);
        static readonly Color Dim = Color.FromArgb(139, 148, 163);

        static readonly string DataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GazLiveMixer");
        static readonly string LastFile = Path.Combine(DataDir, "last.txt");
        static readonly HttpClient Http = new HttpClient { Timeout = TimeSpan.FromMilliseconds(1500) };

        readonly Panel picker;
        readonly Label status;
        readonly FlowLayoutPanel list;
        readonly TextBox address;
        WebView2 web;
        bool webReady, scanning, fullScreen;
        FormWindowState prevState;
        Mixer current;

        public MainForm()
        {
            Text = "Gaz Live Mixer";
            BackColor = Bg;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10f);
            MinimumSize = new Size(480, 420);
            Size = new Size(1280, 800);
            StartPosition = FormStartPosition.CenterScreen;
            WindowState = FormWindowState.Maximized;
            KeyPreview = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            picker = new Panel { Dock = DockStyle.Fill, BackColor = Bg, AutoScroll = true };
            var col = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true,
                Padding = new Padding(40, 36, 40, 30), BackColor = Bg
            };
            picker.Controls.Add(col);

            col.Controls.Add(new Label
            {
                Text = "GAZ LIVE MIXER", AutoSize = true, ForeColor = Color.White,
                Font = new Font("Segoe UI", 24f, FontStyle.Bold)
            });
            col.Controls.Add(new Label
            {
                Text = "Control OBS like a video mixer", AutoSize = true, ForeColor = Dim,
                Margin = new Padding(3, 0, 3, 22)
            });
            status = new Label { AutoSize = true, ForeColor = Dim, MaximumSize = new Size(560, 0), Margin = new Padding(3, 0, 3, 10) };
            col.Controls.Add(status);
            list = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true };
            col.Controls.Add(list);
            col.Controls.Add(MakeButton("Search again", Panel2, (s, e) => Scan(false)));

            col.Controls.Add(new Label
            {
                Text = "Or type the address shown in OBS", AutoSize = true, ForeColor = Dim,
                Margin = new Padding(3, 26, 3, 6)
            });
            address = new TextBox
            {
                Width = 420, BackColor = Panel2, ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Segoe UI", 12f)
            };
            address.KeyDown += (s, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; ConnectTyped(); } };
            col.Controls.Add(address);
            col.Controls.Add(MakeButton("Connect", Red, (s, e) => ConnectTyped()));
            col.Controls.Add(new Label
            {
                Text = "Shortcuts: F11 full screen · F5 reload · Ctrl+L choose another OBS PC\n" +
                       "In the mixer: 1–9 select · Shift+number take · Space AUTO · C CUT",
                AutoSize = true, ForeColor = Dim, Margin = new Padding(3, 26, 3, 0)
            });
            col.Controls.Add(new Label
            {
                Text = "Gaz Media Production · Everything Multimedia", AutoSize = true,
                ForeColor = Color.FromArgb(90, 98, 112), Margin = new Padding(3, 8, 3, 0)
            });
            Controls.Add(picker);

            Shown += async (s, e) => await StartAsync();
        }

        Button MakeButton(string text, Color color, EventHandler onClick)
        {
            var b = new Button
            {
                Text = text, Width = 420, Height = 46, FlatStyle = FlatStyle.Flat, BackColor = color,
                ForeColor = Color.White, Font = new Font("Segoe UI", 11f, FontStyle.Bold),
                Margin = new Padding(3, 10, 3, 0), Cursor = Cursors.Hand
            };
            b.FlatAppearance.BorderSize = 0;
            b.Click += onClick;
            return b;
        }

        /* ---------------------------------------------------------------- startup */

        async Task StartAsync()
        {
            try { Directory.CreateDirectory(DataDir); } catch { }
            string last = null;
            try { if (File.Exists(LastFile)) last = File.ReadAllText(LastFile).Trim(); } catch { }
            if (!string.IsNullOrEmpty(last)) address.Text = last;

            if (!string.IsNullOrEmpty(last))
            {
                status.Text = "Reconnecting to " + last + "…";
                var m = Parse(last);
                if (m != null && await IsMixer(m)) { await OpenAsync(m); return; }
            }
            Scan(true);
        }

        static Mixer Parse(string text)
        {
            var t = (text ?? "").Trim();
            t = Regex.Replace(t, "^https?://", "", RegexOptions.IgnoreCase).TrimEnd('/');
            if (t.Length == 0) return null;
            int port = DefaultPort;
            var host = t;
            int i = t.LastIndexOf(':');
            if (i > 0 && int.TryParse(t.Substring(i + 1), out var p)) { port = p; host = t.Substring(0, i); }
            return new Mixer { Name = host, Host = host, Port = port };
        }

        static async Task<bool> IsMixer(Mixer m)
        {
            try
            {
                var s = await Http.GetStringAsync($"http://{m.Host}:{m.Port}/api/info");
                return s.Contains("gaz-live-mixer");
            }
            catch { return false; }
        }

        void ConnectTyped()
        {
            var m = Parse(address.Text);
            if (m == null) return;
            _ = OpenAsync(m);
        }

        /* ---------------------------------------------------------------- discovery */

        async void Scan(bool autoConnect)
        {
            if (scanning) return;
            scanning = true;
            status.Text = "Looking for OBS on this network…";
            list.Controls.Clear();
            List<Mixer> found;
            try { found = await Task.Run(Discover); }
            catch { found = new List<Mixer>(); }

            // The mixer may be running on this same PC
            var local = new Mixer { Name = "This PC", Host = "127.0.0.1", Port = DefaultPort };
            if (!found.Any() && await IsMixer(local)) found.Add(local);
            scanning = false;

            if (found.Count == 0)
            {
                status.Text = "No OBS mixer found. Check that OBS is open with the Gaz Live Mixer plugin, " +
                              "and that this PC is on the same network as the OBS PC.";
                return;
            }
            status.Text = found.Count == 1 ? "Found 1 OBS mixer:" : $"Found {found.Count} OBS mixers:";
            foreach (var m in found)
            {
                var mm = m;
                var b = MakeButton($"{m.Name}    {m.Address}", Panel1, async (s, e) => await OpenAsync(mm));
                b.TextAlign = ContentAlignment.MiddleLeft;
                b.Padding = new Padding(12, 0, 0, 0);
                list.Controls.Add(b);
            }
            if (autoConnect && found.Count == 1 && current == null) await OpenAsync(found[0]);
        }

        static List<Mixer> Discover()
        {
            var results = new Dictionary<string, Mixer>();
            var msg = Encoding.ASCII.GetBytes("GAZMIXER_DISCOVER");
            var targets = new List<IPAddress> { IPAddress.Broadcast };
            targets.AddRange(SubnetBroadcasts());
            using (var udp = new UdpClient(0))
            {
                udp.EnableBroadcast = true;
                udp.Client.ReceiveTimeout = 300;
                var until = DateTime.UtcNow.AddMilliseconds(1800);
                var nextSend = DateTime.MinValue;
                while (DateTime.UtcNow < until)
                {
                    if (DateTime.UtcNow >= nextSend)
                    {
                        foreach (var t in targets)
                            try { udp.Send(msg, msg.Length, new IPEndPoint(t, DiscoveryPort)); } catch { }
                        nextSend = DateTime.UtcNow.AddMilliseconds(600);
                    }
                    try
                    {
                        var from = new IPEndPoint(IPAddress.Any, 0);
                        var data = udp.Receive(ref from);
                        var json = Encoding.UTF8.GetString(data);
                        if (!json.Contains("\"gaz-live-mixer\"")) continue;
                        var m = new Mixer
                        {
                            Host = from.Address.ToString(),
                            Name = JsonField(json, "name") ?? from.Address.ToString(),
                            Port = int.TryParse(JsonField(json, "port"), out var p) ? p : DefaultPort
                        };
                        results[m.Url] = m;
                    }
                    catch (SocketException) { }
                    catch { }
                }
            }
            return results.Values.ToList();
        }

        static IEnumerable<IPAddress> SubnetBroadcasts()
        {
            var list = new List<IPAddress>();
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                        var ip = ua.Address.GetAddressBytes();
                        var mask = ua.IPv4Mask.GetAddressBytes();
                        if (ip[0] == 127) continue;
                        var b = new byte[4];
                        for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                        list.Add(new IPAddress(b));
                    }
                }
            }
            catch { }
            return list;
        }

        static string JsonField(string json, string key)
        {
            var m = Regex.Match(json, "\"" + key + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(\\d+))");
            if (!m.Success) return null;
            return m.Groups[2].Success && m.Groups[1].Value.StartsWith("\"") ? Regex.Unescape(m.Groups[2].Value) : m.Groups[3].Value;
        }

        /* ---------------------------------------------------------------- mixer view */

        async Task EnsureWebAsync()
        {
            if (webReady) return;
            web = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Bg, Visible = false };
            Controls.Add(web);
            web.BringToFront();
            try
            {
                try
                {
                    var arch = System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture.ToString().ToLowerInvariant();
                    var loaderDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "runtimes", "win-" + arch, "native");
                    if (File.Exists(Path.Combine(loaderDir, "WebView2Loader.dll")))
                        CoreWebView2Environment.SetLoaderDllFolderPath(loaderDir);
                }
                catch { }
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(DataDir, "WebView2"));
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                Controls.Remove(web);
                web.Dispose();
                web = null;
                var r = MessageBox.Show(this,
                    "Gaz Live Mixer needs the Microsoft Edge WebView2 Runtime, which is missing on this PC.\n\n" +
                    "Open the download page now?\n\n(" + ex.Message + ")",
                    "Gaz Live Mixer", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (r == DialogResult.Yes)
                    System.Diagnostics.Process.Start("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
                throw;
            }
            var s = web.CoreWebView2.Settings;
            s.AreDevToolsEnabled = false;
            s.AreDefaultContextMenusEnabled = false;
            s.IsStatusBarEnabled = false;
            web.CoreWebView2.DocumentTitleChanged += (o, e) =>
                Text = current == null ? "Gaz Live Mixer" : $"Gaz Live Mixer – {current.Name}";
            web.CoreWebView2.NavigationCompleted += (o, e) =>
            {
                if (!e.IsSuccess && current != null)
                {
                    var failed = current;
                    ShowPicker();
                    status.Text = $"Could not open {failed.Address} ({e.WebErrorStatus}). Is OBS running with the Gaz Live Mixer plugin?";
                }
            };
            // links that open new windows go to the default browser
            web.CoreWebView2.NewWindowRequested += (o, e) =>
            {
                e.Handled = true;
                try { System.Diagnostics.Process.Start(e.Uri); } catch { }
            };
            webReady = true;
        }

        async Task OpenAsync(Mixer m)
        {
            try { await EnsureWebAsync(); } catch { return; }
            current = m;
            try { File.WriteAllText(LastFile, m.Address); } catch { }
            address.Text = m.Address;
            Text = $"Gaz Live Mixer – {m.Name}";
            web.Visible = true;
            picker.Visible = false;
            web.CoreWebView2.Navigate(m.Url);
            web.Focus();
        }

        void ShowPicker()
        {
            current = null;
            if (web != null) web.Visible = false;
            picker.Visible = true;
            Text = "Gaz Live Mixer";
            if (fullScreen) ToggleFullScreen();
        }

        void ToggleFullScreen()
        {
            fullScreen = !fullScreen;
            if (fullScreen)
            {
                prevState = WindowState;
                FormBorderStyle = FormBorderStyle.None;
                WindowState = FormWindowState.Normal;
                WindowState = FormWindowState.Maximized;
            }
            else
            {
                FormBorderStyle = FormBorderStyle.Sizable;
                WindowState = prevState == FormWindowState.Minimized ? FormWindowState.Maximized : prevState;
            }
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            switch (keyData)
            {
                case Keys.F11:
                    ToggleFullScreen();
                    return true;
                case Keys.Escape when fullScreen:
                    ToggleFullScreen();
                    return true;
                case Keys.F5 when current != null && webReady:
                    web.CoreWebView2.Reload();
                    return true;
                case Keys.Control | Keys.L:
                    ShowPicker();
                    Scan(false);
                    return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }
    }
}
