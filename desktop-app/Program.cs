using System;
using System.Threading;
using System.Windows.Forms;

namespace GazLiveMixer
{
    internal static class Program
    {
        [STAThread]
        static void Main()
        {
            using (var single = new Mutex(true, "GazLiveMixer.Desktop", out _))
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }
    }
}
