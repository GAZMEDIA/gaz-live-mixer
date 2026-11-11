/*
 * Gaz Live Mixer - native OBS Studio plugin
 * Gaz Media Production - "Everything Multimedia"
 *
 * Control OBS like a video mixer from phones, tablets or other PCs on the
 * same network. No WebSocket setup: the plugin runs inside OBS and serves a
 * mixer panel over plain HTTP, with live scene thumbnails, tally, CUT/AUTO,
 * transitions, hotkeys and LAN auto-discovery for the Android app.
 *
 * Requires OBS Studio 30 or newer.
 */

#define CPPHTTPLIB_THREAD_POOL_COUNT 48
#include "httplib.h"

#include <obs-module.h>
#include <obs-frontend-api.h>
#include <util/platform.h>
#include <graphics/vec4.h>

#include <QApplication>
#include <QBuffer>
#include <QClipboard>
#include <QComboBox>
#include <QDesktopServices>
#include <QHBoxLayout>
#include <QImage>
#include <QLabel>
#include <QPainter>
#include <QPushButton>
#include <QSpinBox>
#include <QTimer>
#include <QUrl>
#include <QVBoxLayout>
#include <QWidget>

#include "qrcodegen/qrcodegen.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
typedef SOCKET sock_t;
#define CLOSESOCK closesocket
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
typedef int sock_t;
#define INVALID_SOCKET (-1)
#define CLOSESOCK close
#endif

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("gaz-live-mixer", "en-US")

#define PLUGIN_VERSION "1.0.0"
#define DEFAULT_PORT 4466
#define DISCOVERY_PORT 4467
#define HOTKEY_SLOTS 12
#define blog_gaz(level, fmt, ...) blog(level, "[Gaz Live Mixer] " fmt, ##__VA_ARGS__)

using Clock = std::chrono::steady_clock;

/* ------------------------------------------------------------------------ */
/* Shared state                                                              */
/* ------------------------------------------------------------------------ */
struct Command {
	std::string cmd, scene, mode, arg;
};

struct Thumb {
	std::string data, mime;
	Clock::time_point at{};
};

static struct Shared {
	std::mutex mtx;
	std::condition_variable cv;
	std::string state_json = "{}";
	std::vector<std::string> scenes;
	long long rev = 0;
	std::deque<Command> commands;
	std::map<std::string, Thumb> thumbs;
	std::map<std::string, Clock::time_point> wanted;
	std::atomic<int> thumb_width{320};
	std::atomic<bool> thumbs_enabled{true};
	std::atomic<bool> running{false};
	int port = DEFAULT_PORT;
} SH;

static void push_command(Command c)
{
	std::lock_guard<std::mutex> l(SH.mtx);
	SH.commands.push_back(std::move(c));
}

/* ------------------------------------------------------------------------ */
/* Helpers                                                                   */
/* ------------------------------------------------------------------------ */
static std::string json_str(const char *s)
{
	if (!s)
		return "null";
	std::string o = "\"";
	for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
		switch (*p) {
		case '"': o += "\\\""; break;
		case '\\': o += "\\\\"; break;
		case '\n': o += "\\n"; break;
		case '\r': o += "\\r"; break;
		case '\t': o += "\\t"; break;
		default:
			if (*p < 0x20) {
				char b[8];
				snprintf(b, sizeof(b), "\\u%04x", *p);
				o += b;
			} else {
				o += (char)*p;
			}
		}
	}
	return o + "\"";
}

static std::string primary_ip()
{
	std::string ip = "127.0.0.1";
	sock_t s = socket(AF_INET, SOCK_DGRAM, 0);
	if (s == INVALID_SOCKET)
		return ip;
	sockaddr_in a{};
	a.sin_family = AF_INET;
	a.sin_port = htons(1);
	inet_pton(AF_INET, "10.255.255.255", &a.sin_addr);
	if (connect(s, (sockaddr *)&a, sizeof(a)) == 0) {
		sockaddr_in me{};
		socklen_t len = sizeof(me);
		if (getsockname(s, (sockaddr *)&me, &len) == 0) {
			char buf[64];
			inet_ntop(AF_INET, &me.sin_addr, buf, sizeof(buf));
			ip = buf;
		}
	}
	CLOSESOCK(s);
	return ip;
}

static std::string panel_url()
{
	return "http://" + primary_ip() + ":" + std::to_string(SH.port) + "/";
}

static std::string host_name()
{
	char buf[256] = {0};
	if (gethostname(buf, sizeof(buf) - 1) != 0)
		return "OBS";
	return buf;
}

static std::string read_data_file(const char *name)
{
	char *path = obs_module_file(name);
	std::string out;
	if (path) {
		std::ifstream f(path, std::ios::binary);
		std::stringstream ss;
		ss << f.rdbuf();
		out = ss.str();
		bfree(path);
	}
	return out;
}

static std::string qr_svg(const std::string &text)
{
	using qrcodegen::QrCode;
	QrCode qr = QrCode::encodeText(text.c_str(), QrCode::Ecc::MEDIUM);
	int n = qr.getSize(), border = 2, size = n + border * 2;
	std::string path;
	for (int y = 0; y < n; y++)
		for (int x = 0; x < n; x++)
			if (qr.getModule(x, y))
				path += "M" + std::to_string(x + border) + "," + std::to_string(y + border) + "h1v1h-1z";
	return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + std::to_string(size) + " " +
	       std::to_string(size) +
	       "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"" +
	       path + "\" fill=\"#000\"/></svg>";
}

static QImage qr_image(const std::string &text, int px)
{
	using qrcodegen::QrCode;
	QrCode qr = QrCode::encodeText(text.c_str(), QrCode::Ecc::MEDIUM);
	int n = qr.getSize(), border = 2, size = n + border * 2;
	QImage img(size, size, QImage::Format_RGB32);
	img.fill(Qt::white);
	for (int y = 0; y < n; y++)
		for (int x = 0; x < n; x++)
			if (qr.getModule(x, y))
				img.setPixel(x + border, y + border, qRgb(0, 0, 0));
	return img.scaled(px, px, Qt::KeepAspectRatio, Qt::FastTransformation);
}

/* ------------------------------------------------------------------------ */
/* OBS side (UI thread only): state snapshot and command execution           */
/* ------------------------------------------------------------------------ */
static std::atomic<bool> g_ready{false};
static std::string g_restore_transition;
static Clock::time_point g_restore_at{};

static std::string src_name(obs_source_t *s)
{
	const char *n = s ? obs_source_get_name(s) : nullptr;
	return n ? n : "";
}

static std::vector<std::string> scene_names()
{
	std::vector<std::string> out;
	char **names = obs_frontend_get_scene_names();
	if (names) {
		for (char **p = names; *p; p++)
			out.emplace_back(*p);
		bfree(names);
	}
	return out;
}

static std::string current_transition_name()
{
	obs_source_t *t = obs_frontend_get_current_transition();
	std::string n = src_name(t);
	obs_source_release(t);
	return n;
}

static void set_transition(const std::string &name)
{
	struct obs_frontend_source_list list = {};
	obs_frontend_get_transitions(&list);
	for (size_t i = 0; i < list.sources.num; i++) {
		if (src_name(list.sources.array[i]) == name) {
			obs_frontend_set_current_transition(list.sources.array[i]);
			break;
		}
	}
	obs_frontend_source_list_free(&list);
}

static std::string cut_transition_name()
{
	std::string found;
	struct obs_frontend_source_list list = {};
	obs_frontend_get_transitions(&list);
	for (size_t i = 0; i < list.sources.num; i++) {
		const char *id = obs_source_get_id(list.sources.array[i]);
		if (id && strcmp(id, "cut_transition") == 0) {
			found = src_name(list.sources.array[i]);
			break;
		}
	}
	obs_frontend_source_list_free(&list);
	return found;
}

template<typename F> static void with_cut(F fn)
{
	std::string cut = cut_transition_name(), cur = current_transition_name();
	if (cut.empty() || cut == cur) {
		fn();
		return;
	}
	if (g_restore_transition.empty())
		g_restore_transition = cur;
	set_transition(cut);
	fn();
	g_restore_at = Clock::now() + std::chrono::milliseconds(300);
}

static void take_scene(const std::string &scene, const std::string &mode)
{
	obs_source_t *src = obs_get_source_by_name(scene.c_str());
	if (!src)
		return;
	auto go = [src]() {
		if (obs_frontend_preview_program_mode_active()) {
			obs_frontend_set_current_preview_scene(src);
			obs_frontend_preview_program_trigger_transition();
		} else {
			obs_frontend_set_current_scene(src);
		}
	};
	if (mode == "cut")
		with_cut(go);
	else
		go();
	obs_source_release(src);
}

static void step_scene(int delta)
{
	auto names = scene_names();
	if (names.empty())
		return;
	bool studio = obs_frontend_preview_program_mode_active();
	obs_source_t *cur = studio ? obs_frontend_get_current_preview_scene() : obs_frontend_get_current_scene();
	std::string nm = src_name(cur);
	obs_source_release(cur);
	int i = -1;
	for (size_t k = 0; k < names.size(); k++)
		if (names[k] == nm)
			i = (int)k;
	int n = (int)names.size();
	const std::string &next = names[((i + delta) % n + n) % n];
	if (studio) {
		obs_source_t *s = obs_get_source_by_name(next.c_str());
		if (s) {
			obs_frontend_set_current_preview_scene(s);
			obs_source_release(s);
		}
	} else {
		take_scene(next, "auto");
	}
}

static void run_command(const Command &c)
{
	bool studio = obs_frontend_preview_program_mode_active();
	if (c.cmd == "take") {
		take_scene(c.scene, c.mode);
	} else if (c.cmd == "preview") {
		if (studio) {
			obs_source_t *s = obs_get_source_by_name(c.scene.c_str());
			if (s) {
				obs_frontend_set_current_preview_scene(s);
				obs_source_release(s);
			}
		}
	} else if (c.cmd == "auto" || c.cmd == "cut") {
		if (studio) {
			if (c.cmd == "cut")
				with_cut([]() { obs_frontend_preview_program_trigger_transition(); });
			else
				obs_frontend_preview_program_trigger_transition();
		}
	} else if (c.cmd == "transition" && !c.arg.empty()) {
		set_transition(c.arg);
		g_restore_transition.clear();
	} else if (c.cmd == "duration" && !c.arg.empty()) {
		int ms = atoi(c.arg.c_str());
		obs_frontend_set_transition_duration(std::max(50, std::min(20000, ms)));
	} else if (c.cmd == "studio") {
		bool on = (c.arg.empty() || c.arg == "toggle") ? !studio : (c.arg == "1" || c.arg == "true" || c.arg == "on");
		obs_frontend_set_preview_program_mode(on);
	} else if (c.cmd == "next") {
		step_scene(1);
	} else if (c.cmd == "prev") {
		step_scene(-1);
	}
}

static std::string build_state(std::vector<std::string> &names_out)
{
	names_out = scene_names();
	obs_source_t *cur = obs_frontend_get_current_scene();
	std::string program = src_name(cur);
	obs_source_release(cur);
	bool studio = obs_frontend_preview_program_mode_active();
	std::string preview;
	if (studio) {
		obs_source_t *pv = obs_frontend_get_current_preview_scene();
		preview = src_name(pv);
		obs_source_release(pv);
	}

	std::string o = "{\"app\":\"gaz-live-mixer\",\"edition\":\"native\",\"version\":\"" PLUGIN_VERSION "\"";
	o += ",\"host\":" + json_str(host_name().c_str());
	o += ",\"scenes\":[";
	for (size_t i = 0; i < names_out.size(); i++)
		o += (i ? "," : "") + json_str(names_out[i].c_str());
	o += "],\"program\":" + (program.empty() ? std::string("null") : json_str(program.c_str()));
	o += ",\"preview\":" + (preview.empty() ? std::string("null") : json_str(preview.c_str()));
	o += std::string(",\"studio\":") + (studio ? "true" : "false");

	o += ",\"transitions\":[";
	struct obs_frontend_source_list list = {};
	obs_frontend_get_transitions(&list);
	for (size_t i = 0; i < list.sources.num; i++) {
		obs_source_t *t = list.sources.array[i];
		o += std::string(i ? "," : "") + "{\"name\":" + json_str(obs_source_get_name(t)) +
		     ",\"kind\":" + json_str(obs_source_get_id(t)) +
		     ",\"fixed\":" + (obs_transition_fixed(t) ? "true" : "false") + "}";
	}
	obs_frontend_source_list_free(&list);
	o += "],\"transition\":" + json_str(current_transition_name().c_str());
	o += ",\"duration\":" + std::to_string(obs_frontend_get_transition_duration());
	o += std::string(",\"streaming\":") + (obs_frontend_streaming_active() ? "true" : "false");
	o += std::string(",\"recording\":") + (obs_frontend_recording_active() ? "true" : "false");
	o += std::string(",\"thumbnails\":") + (SH.thumbs_enabled ? "true" : "false");
	o += "}";
	return o;
}

static void ui_tick()
{
	if (!g_ready)
		return;
	std::deque<Command> cmds;
	{
		std::lock_guard<std::mutex> l(SH.mtx);
		cmds.swap(SH.commands);
	}
	for (auto &c : cmds)
		run_command(c);
	if (!g_restore_transition.empty() && Clock::now() >= g_restore_at) {
		set_transition(g_restore_transition);
		g_restore_transition.clear();
	}
	std::vector<std::string> names;
	std::string st = build_state(names);
	std::lock_guard<std::mutex> l(SH.mtx);
	if (st != SH.state_json) {
		SH.state_json = st;
		SH.scenes = names;
		SH.rev++;
		SH.cv.notify_all();
	}
}

/* ------------------------------------------------------------------------ */
/* Thumbnails: render scenes off-screen on a worker thread                   */
/* ------------------------------------------------------------------------ */
static std::thread g_thumb_thread;
static gs_texrender_t *g_tex = nullptr;
static gs_stagesurf_t *g_stage = nullptr;
static uint32_t g_stage_w = 0, g_stage_h = 0;

static bool render_scene(const std::string &name, int width, std::string &out, std::string &mime)
{
	obs_source_t *src = obs_get_source_by_name(name.c_str());
	if (!src)
		return false;
	uint32_t bw = obs_source_get_base_width(src), bh = obs_source_get_base_height(src);
	if (!bw || !bh) {
		obs_source_release(src);
		return false;
	}
	uint32_t w = (uint32_t)std::max(16, width) & ~1u;
	uint32_t h = (uint32_t)std::max(16.0, (double)w * bh / bw + 0.5) & ~1u;
	QImage img;

	obs_enter_graphics();
	if (!g_tex)
		g_tex = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	if (!g_stage || g_stage_w != w || g_stage_h != h) {
		if (g_stage)
			gs_stagesurface_destroy(g_stage);
		g_stage = gs_stagesurface_create(w, h, GS_RGBA);
		g_stage_w = w;
		g_stage_h = h;
	}
	gs_texrender_reset(g_tex);
	if (gs_texrender_begin(g_tex, w, h)) {
		struct vec4 bg;
		vec4_set(&bg, 0.0f, 0.0f, 0.0f, 1.0f);
		gs_clear(GS_CLEAR_COLOR, &bg, 0.0f, 0);
		gs_ortho(0.0f, (float)bw, 0.0f, (float)bh, -100.0f, 100.0f);
		gs_blend_state_push();
		gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);
		obs_source_inc_showing(src);
		obs_source_video_render(src);
		obs_source_dec_showing(src);
		gs_blend_state_pop();
		gs_texrender_end(g_tex);
		gs_stage_texture(g_stage, gs_texrender_get_texture(g_tex));
		uint8_t *data = nullptr;
		uint32_t linesize = 0;
		if (gs_stagesurface_map(g_stage, &data, &linesize)) {
			img = QImage(data, (int)w, (int)h, (int)linesize, QImage::Format_RGBA8888).copy();
			gs_stagesurface_unmap(g_stage);
		}
	}
	obs_leave_graphics();
	obs_source_release(src);

	if (img.isNull())
		return false;
	QImage rgb = img.convertToFormat(QImage::Format_RGB888);
	QByteArray bytes;
	QBuffer buf(&bytes);
	buf.open(QIODevice::WriteOnly);
	if (rgb.save(&buf, "JPG", 72)) {
		mime = "image/jpeg";
	} else {
		bytes.clear();
		buf.seek(0);
		rgb.save(&buf, "PNG");
		mime = "image/png";
	}
	out.assign(bytes.constData(), (size_t)bytes.size());
	return !out.empty();
}

static void thumb_loop()
{
	while (SH.running) {
		std::this_thread::sleep_for(std::chrono::milliseconds(40));
		if (!SH.thumbs_enabled || !g_ready)
			continue;
		auto now = Clock::now();
		std::string pick;
		Clock::time_point oldest = Clock::time_point::max();
		{
			std::lock_guard<std::mutex> l(SH.mtx);
			for (auto &kv : SH.wanted) {
				if (now - kv.second > std::chrono::seconds(4))
					continue;
				auto it = SH.thumbs.find(kv.first);
				Clock::time_point at = it == SH.thumbs.end() ? Clock::time_point{} : it->second.at;
				if (at < oldest) {
					oldest = at;
					pick = kv.first;
				}
			}
		}
		if (pick.empty() || now - oldest < std::chrono::milliseconds(350))
			continue;
		std::string data, mime;
		bool ok = render_scene(pick, SH.thumb_width, data, mime);
		std::lock_guard<std::mutex> l(SH.mtx);
		Thumb &t = SH.thumbs[pick];
		if (ok) {
			t.data = std::move(data);
			t.mime = mime;
		}
		t.at = Clock::now();
	}
	obs_enter_graphics();
	if (g_stage)
		gs_stagesurface_destroy(g_stage);
	if (g_tex)
		gs_texrender_destroy(g_tex);
	g_stage = nullptr;
	g_tex = nullptr;
	g_stage_w = g_stage_h = 0;
	obs_leave_graphics();
}

/* ------------------------------------------------------------------------ */
/* Web server + LAN discovery                                                */
/* ------------------------------------------------------------------------ */
static httplib::Server *g_svr = nullptr;
static std::thread g_http_thread, g_udp_thread;
static sock_t g_udp = INVALID_SOCKET;

static void send_json(httplib::Response &res, const std::string &body, int status = 200)
{
	res.status = status;
	res.set_header("Cache-Control", "no-store");
	res.set_content(body, "application/json");
}

static bool scene_exists(const std::string &name)
{
	std::lock_guard<std::mutex> l(SH.mtx);
	for (auto &s : SH.scenes)
		if (s == name)
			return true;
	return false;
}

static void add_command_route(httplib::Server &svr, const char *path, const char *cmd, bool needs_scene,
			      const char *argname = nullptr)
{
	std::string c = cmd, a = argname ? argname : "";
	auto h = [c, a, needs_scene](const httplib::Request &req, httplib::Response &res) {
		Command k;
		k.cmd = c;
		if (needs_scene) {
			k.scene = req.get_param_value("scene");
			if (!scene_exists(k.scene))
				return send_json(res, "{\"error\":" + json_str(("Scene not found: " + k.scene).c_str()) + "}", 404);
		}
		k.mode = req.has_param("mode") ? req.get_param_value("mode") : "auto";
		if (!a.empty())
			k.arg = req.get_param_value(a);
		push_command(k);
		send_json(res, "{\"ok\":true}");
	};
	svr.Get(path, h);
	svr.Post(path, h);
}

static void discovery_loop()
{
	g_udp = socket(AF_INET, SOCK_DGRAM, 0);
	if (g_udp == INVALID_SOCKET)
		return;
	int yes = 1;
	setsockopt(g_udp, SOL_SOCKET, SO_REUSEADDR, (const char *)&yes, sizeof(yes));
#ifdef _WIN32
	DWORD tv = 500;
	setsockopt(g_udp, SOL_SOCKET, SO_RCVTIMEO, (const char *)&tv, sizeof(tv));
#else
	timeval tv{0, 500000};
	setsockopt(g_udp, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif
	sockaddr_in a{};
	a.sin_family = AF_INET;
	a.sin_port = htons(DISCOVERY_PORT);
	a.sin_addr.s_addr = htonl(INADDR_ANY);
	if (bind(g_udp, (sockaddr *)&a, sizeof(a)) != 0) {
		blog_gaz(LOG_WARNING, "LAN discovery port %d busy; Android auto-find disabled", DISCOVERY_PORT);
		CLOSESOCK(g_udp);
		g_udp = INVALID_SOCKET;
		return;
	}
	char buf[512];
	while (SH.running) {
		sockaddr_in from{};
		socklen_t fl = sizeof(from);
		int n = (int)recvfrom(g_udp, buf, sizeof(buf), 0, (sockaddr *)&from, &fl);
		if (n <= 0)
			continue;
		if (n >= 17 && memcmp(buf, "GAZMIXER_DISCOVER", 17) == 0) {
			std::string r = "{\"app\":\"gaz-live-mixer\",\"name\":" + json_str(host_name().c_str()) +
					",\"port\":" + std::to_string(SH.port) + ",\"version\":\"" PLUGIN_VERSION "\"}";
			sendto(g_udp, r.data(), (int)r.size(), 0, (sockaddr *)&from, fl);
		}
	}
	CLOSESOCK(g_udp);
	g_udp = INVALID_SOCKET;
}

static void stop_server();

static std::mutex g_svr_mtx;
static std::atomic<bool> g_http_done{true};

static httplib::Server *make_server()
{
	auto *s = new httplib::Server();
	httplib::Server &svr = *s;
	svr.set_keep_alive_timeout(3);
	svr.set_socket_options([](socket_t sock) {
#ifndef _WIN32
		/* allow rebinding while old connections sit in TIME_WAIT after an OBS restart */
		int yes = 1;
		setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
#else
		(void)sock;
#endif
	});
	svr.set_default_headers({{"Access-Control-Allow-Origin", "*"}});

	svr.Get("/", [](const httplib::Request &, httplib::Response &res) {
		std::string page = read_data_file("panel.html");
		res.set_header("Cache-Control", "no-store");
		res.set_content(page.empty() ? "<h1>panel.html missing from plugin data folder</h1>" : page,
				"text/html; charset=utf-8");
	});
	svr.Get("/connect", [](const httplib::Request &, httplib::Response &res) {
		res.set_content(read_data_file("connect.html"), "text/html; charset=utf-8");
	});
	svr.Get("/qr.svg", [](const httplib::Request &req, httplib::Response &res) {
		std::string u = req.has_param("u") ? req.get_param_value("u").substr(0, 300) : panel_url();
		res.set_content(qr_svg(u), "image/svg+xml");
	});
	svr.Get("/api/info", [](const httplib::Request &, httplib::Response &res) {
		std::string u = panel_url();
		send_json(res, "{\"app\":\"gaz-live-mixer\",\"edition\":\"native\",\"version\":\"" PLUGIN_VERSION
			       "\",\"name\":" + json_str(host_name().c_str()) + ",\"port\":" + std::to_string(SH.port) +
				       ",\"url\":" + json_str(u.c_str()) + ",\"urls\":[" + json_str(u.c_str()) + "]}");
	});
	svr.Get("/api/state", [](const httplib::Request &req, httplib::Response &res) {
		long long since = req.has_param("since") ? atoll(req.get_param_value("since").c_str()) : -1;
		double wait = req.has_param("wait") ? atof(req.get_param_value("wait").c_str()) : 0;
		wait = std::max(0.0, std::min(25.0, wait));
		std::unique_lock<std::mutex> l(SH.mtx);
		if (wait > 0 && since == SH.rev)
			SH.cv.wait_for(l, std::chrono::milliseconds((int)(wait * 1000)),
				       [since]() { return SH.rev != since || !SH.running; });
		std::string body = SH.state_json;
		if (body.size() > 1)
			body.insert(body.size() - 1, ",\"rev\":" + std::to_string(SH.rev));
		l.unlock();
		send_json(res, body);
	});
	svr.Get("/api/thumb", [](const httplib::Request &req, httplib::Response &res) {
		std::string name = req.get_param_value("scene");
		std::lock_guard<std::mutex> l(SH.mtx);
		SH.wanted[name] = Clock::now();
		auto it = SH.thumbs.find(name);
		if (it == SH.thumbs.end() || it->second.data.empty())
			return send_json(res, "{\"error\":\"No thumbnail yet\"}", 404);
		res.set_header("Cache-Control", "no-store");
		res.set_content(it->second.data, it->second.mime);
	});
	add_command_route(svr, "/api/take", "take", true);
	add_command_route(svr, "/api/preview", "preview", true);
	add_command_route(svr, "/api/auto", "auto", false);
	add_command_route(svr, "/api/cut", "cut", false);
	add_command_route(svr, "/api/transition", "transition", false, "name");
	add_command_route(svr, "/api/duration", "duration", false, "ms");
	add_command_route(svr, "/api/studio", "studio", false, "on");
	add_command_route(svr, "/api/next", "next", false);
	add_command_route(svr, "/api/prev", "prev", false);
	svr.Options(R"(.*)", [](const httplib::Request &, httplib::Response &res) {
		res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		res.status = 204;
	});
	return s;
}

static bool start_server(int port)
{
	stop_server();
	SH.port = port;
	SH.running = true;
	g_http_done = false;
	g_http_thread = std::thread([port]() {
		struct Done {
			~Done() { g_http_done = true; }
		} done;
		/* After an OBS restart the old port can stay busy for a few seconds: keep trying.
		 * httplib refuses to reuse a Server after a failed bind, so build a fresh one each time. */
		for (int attempt = 0; attempt < 30 && SH.running; attempt++) {
			httplib::Server *s = make_server();
			if (s->bind_to_port("0.0.0.0", port)) {
				{
					std::lock_guard<std::mutex> l(g_svr_mtx);
					if (!SH.running) {
						delete s;
						return;
					}
					g_svr = s;
				}
				blog_gaz(LOG_INFO, "Mixer panel running at %s", panel_url().c_str());
				s->listen_after_bind();
				return;
			}
			delete s;
			if (attempt == 0)
				blog_gaz(LOG_WARNING, "Port %d busy, retrying...", port);
			for (int i = 0; i < 20 && SH.running; i++)
				std::this_thread::sleep_for(std::chrono::milliseconds(100));
		}
		if (SH.running)
			blog_gaz(LOG_ERROR, "Could not open port %d. Change the port in the Gaz Live Mixer dock.", port);
	});
	g_udp_thread = std::thread(discovery_loop);
	g_thumb_thread = std::thread(thumb_loop);
	return true;
}

static void stop_server()
{
	SH.running = false;
	{
		std::lock_guard<std::mutex> l(SH.mtx);
		SH.cv.notify_all();
	}
	/* stop() is a no-op if listen() has not started yet, so repeat until the thread ends */
	while (g_http_thread.joinable() && !g_http_done) {
		{
			std::lock_guard<std::mutex> l(g_svr_mtx);
			if (g_svr)
				g_svr->stop();
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(20));
	}
	if (g_http_thread.joinable())
		g_http_thread.join();
	if (g_udp_thread.joinable())
		g_udp_thread.join();
	if (g_thumb_thread.joinable())
		g_thumb_thread.join();
	std::lock_guard<std::mutex> l(g_svr_mtx);
	delete g_svr;
	g_svr = nullptr;
}

/* ------------------------------------------------------------------------ */
/* Settings (port, thumbnails)                                               */
/* ------------------------------------------------------------------------ */
static obs_data_t *load_config()
{
	char *path = obs_module_config_path("config.json");
	obs_data_t *d = path ? obs_data_create_from_json_file_safe(path, "bak") : nullptr;
	bfree(path);
	if (!d)
		d = obs_data_create();
	obs_data_set_default_int(d, "port", DEFAULT_PORT);
	obs_data_set_default_int(d, "thumb_width", 320);
	obs_data_set_default_bool(d, "thumbs", true);
	return d;
}

static void save_config()
{
	char *dir = obs_module_config_path("");
	if (dir) {
		os_mkdirs(dir);
		bfree(dir);
	}
	obs_data_t *d = obs_data_create();
	obs_data_set_int(d, "port", SH.port);
	obs_data_set_int(d, "thumb_width", SH.thumb_width);
	obs_data_set_bool(d, "thumbs", SH.thumbs_enabled);
	char *path = obs_module_config_path("config.json");
	if (path) {
		obs_data_save_json_safe(d, path, "tmp", "bak");
		bfree(path);
	}
	obs_data_release(d);
}

/* ------------------------------------------------------------------------ */
/* Dock: QR code + controls                                                  */
/* ------------------------------------------------------------------------ */
class MixerDock : public QWidget {
public:
	explicit MixerDock(QWidget *parent = nullptr) : QWidget(parent)
	{
		auto *v = new QVBoxLayout(this);
		auto *title = new QLabel("<b>GAZ <span style='color:#ff3b30'>LIVE</span> MIXER</b>");
		title->setAlignment(Qt::AlignCenter);
		qr = new QLabel;
		qr->setAlignment(Qt::AlignCenter);
		url = new QLabel;
		url->setAlignment(Qt::AlignCenter);
		url->setTextInteractionFlags(Qt::TextSelectableByMouse);
		auto *hint = new QLabel("Scan with your phone (same Wi-Fi),\nor open the Gaz Live Mixer app.");
		hint->setAlignment(Qt::AlignCenter);
		hint->setStyleSheet("color:#8b94a3");

		auto *btns = new QHBoxLayout;
		auto *open = new QPushButton("Open mixer");
		auto *copy = new QPushButton("Copy link");
		btns->addWidget(open);
		btns->addWidget(copy);

		auto *opts = new QHBoxLayout;
		port = new QSpinBox;
		port->setRange(1024, 65535);
		port->setValue(SH.port);
		port->setPrefix("Port ");
		quality = new QComboBox;
		quality->addItem("Thumbs off", 0);
		quality->addItem("Low 240px", 240);
		quality->addItem("Normal 320px", 320);
		quality->addItem("High 480px", 480);
		int w = SH.thumbs_enabled ? (int)SH.thumb_width : 0;
		quality->setCurrentIndex(std::max(0, quality->findData(w)));
		auto *apply = new QPushButton("Apply");
		opts->addWidget(port);
		opts->addWidget(quality);
		opts->addWidget(apply);

		v->addWidget(title);
		v->addWidget(qr);
		v->addWidget(url);
		v->addWidget(hint);
		v->addLayout(btns);
		v->addLayout(opts);
		v->addStretch();

		connect(open, &QPushButton::clicked, []() {
			QDesktopServices::openUrl(QUrl(QString("http://127.0.0.1:%1/").arg(SH.port)));
		});
		connect(copy, &QPushButton::clicked, []() {
			QApplication::clipboard()->setText(QString::fromStdString(panel_url()));
		});
		connect(apply, &QPushButton::clicked, [this]() {
			int q = quality->currentData().toInt();
			SH.thumbs_enabled = q > 0;
			if (q > 0)
				SH.thumb_width = q;
			if (port->value() != SH.port || !SH.running)
				start_server(port->value());
			save_config();
			refresh();
		});
		refresh();
	}

	void refresh()
	{
		std::string u = panel_url();
		qr->setPixmap(QPixmap::fromImage(qr_image(u, 200)));
		url->setText(QString("<b>%1</b>").arg(QString::fromStdString(u)));
	}

private:
	QLabel *qr, *url;
	QSpinBox *port;
	QComboBox *quality;
};

/* ------------------------------------------------------------------------ */
/* Hotkeys                                                                   */
/* ------------------------------------------------------------------------ */
struct HotkeyDef {
	std::string key, desc, cmd;
	int slot;
	obs_hotkey_id id;
};
static std::vector<HotkeyDef> g_hotkeys;

static void hotkey_cb(void *data, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (!pressed)
		return;
	HotkeyDef *d = (HotkeyDef *)data;
	Command c;
	c.cmd = d->cmd;
	c.mode = "auto";
	if (d->slot >= 0) {
		std::lock_guard<std::mutex> l(SH.mtx);
		if (d->slot >= (int)SH.scenes.size())
			return;
		c.scene = SH.scenes[d->slot];
	}
	push_command(c);
}

static void register_hotkeys()
{
	g_hotkeys.clear();
	g_hotkeys.push_back({"gazmixer.auto", "Gaz Mixer: AUTO (transition preview to program)", "auto", -1, 0});
	g_hotkeys.push_back({"gazmixer.cut", "Gaz Mixer: CUT (hard cut preview to program)", "cut", -1, 0});
	g_hotkeys.push_back({"gazmixer.next", "Gaz Mixer: Next scene", "next", -1, 0});
	g_hotkeys.push_back({"gazmixer.prev", "Gaz Mixer: Previous scene", "prev", -1, 0});
	g_hotkeys.push_back({"gazmixer.studio", "Gaz Mixer: Toggle studio mode", "studio", -1, 0});
	for (int i = 0; i < HOTKEY_SLOTS; i++) {
		g_hotkeys.push_back({"gazmixer.take" + std::to_string(i + 1),
				     "Gaz Mixer: Take scene " + std::to_string(i + 1) + " live", "take", i, 0});
		g_hotkeys.push_back({"gazmixer.pvw" + std::to_string(i + 1),
				     "Gaz Mixer: Preview scene " + std::to_string(i + 1), "preview", i, 0});
	}
	for (auto &h : g_hotkeys)
		h.id = obs_hotkey_register_frontend(h.key.c_str(), h.desc.c_str(), hotkey_cb, &h);
}

static void save_cb(obs_data_t *save_data, bool saving, void *)
{
	if (saving) {
		obs_data_t *obj = obs_data_create();
		for (auto &h : g_hotkeys) {
			obs_data_array_t *arr = obs_hotkey_save(h.id);
			obs_data_set_array(obj, h.key.c_str(), arr);
			obs_data_array_release(arr);
		}
		obs_data_set_obj(save_data, "gaz-live-mixer", obj);
		obs_data_release(obj);
	} else {
		obs_data_t *obj = obs_data_get_obj(save_data, "gaz-live-mixer");
		if (!obj)
			return;
		for (auto &h : g_hotkeys) {
			obs_data_array_t *arr = obs_data_get_array(obj, h.key.c_str());
			obs_hotkey_load(h.id, arr);
			obs_data_array_release(arr);
		}
		obs_data_release(obj);
	}
}

/* ------------------------------------------------------------------------ */
/* Module entry points                                                       */
/* ------------------------------------------------------------------------ */
static QTimer *g_timer = nullptr;

static void frontend_event(enum obs_frontend_event ev, void *)
{
	if (ev == OBS_FRONTEND_EVENT_FINISHED_LOADING) {
		g_ready = true;
	} else if (ev == OBS_FRONTEND_EVENT_EXIT) {
		g_ready = false;
		if (g_timer) {
			g_timer->stop();
			g_timer->deleteLater();
			g_timer = nullptr;
		}
		stop_server();
	}
}

bool obs_module_load(void)
{
#ifdef _WIN32
	WSADATA wsa;
	WSAStartup(MAKEWORD(2, 2), &wsa);
#endif
	obs_data_t *cfg = load_config();
	SH.port = (int)obs_data_get_int(cfg, "port");
	SH.thumb_width = (int)obs_data_get_int(cfg, "thumb_width");
	SH.thumbs_enabled = obs_data_get_bool(cfg, "thumbs");
	obs_data_release(cfg);

	register_hotkeys();
	obs_frontend_add_save_callback(save_cb, nullptr);
	obs_frontend_add_event_callback(frontend_event, nullptr);

	g_timer = new QTimer();
	QObject::connect(g_timer, &QTimer::timeout, ui_tick);
	g_timer->start(50);

	start_server(SH.port);
	obs_frontend_add_dock_by_id("gaz_live_mixer", "Gaz Live Mixer", new MixerDock());
	blog_gaz(LOG_INFO, "loaded (version %s)", PLUGIN_VERSION);
	return true;
}

void obs_module_unload(void)
{
	stop_server();
	obs_frontend_remove_event_callback(frontend_event, nullptr);
	obs_frontend_remove_save_callback(save_cb, nullptr);
#ifdef _WIN32
	WSACleanup();
#endif
}

const char *obs_module_name(void)
{
	return "Gaz Live Mixer";
}

const char *obs_module_description(void)
{
	return "Control OBS like a video mixer from your phone or another PC. By Gaz Media Production.";
}
