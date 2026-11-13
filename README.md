# GAZ AI
*Your Messages. Your Style. Your Personal AI Assistant.* · Gaz Media Production

Android app that reads incoming chat notifications (WhatsApp, WhatsApp Business, Messenger, Instagram, Telegram, Signal, SMS and any app with a Reply button), writes replies in your style with a free AI, and sends them when you approve, or automatically for chats you choose.

## Free AI brain
- **Google Gemini (default):** free API key from https://aistudio.google.com/apikey
- **Your PC:** install Ollama (https://ollama.com), run `ollama pull llama3.2`, set `OLLAMA_HOST=0.0.0.0`, and enter the PC address in GAZ AI.

## Modes per chat
- **Suggest:** a notification shows 3 replies; tap one to send, or **Edit / more** to change it.
- **Auto-reply:** GAZ AI answers by itself after a short random wait, with a Cancel button. Max 6 auto-replies per chat per hour. Groups never auto-reply unless you allow it.
- **Off:** GAZ AI ignores the chat.

Tones: Friendly, Professional, Romantic, Playful, Business, Nigerian Pidgin.

## Limits
- GAZ AI only sees messages that arrive as notifications after it is installed (no old history, muted chats are invisible).
- Text only (no images or voice notes).
- Heavy auto-replying on WhatsApp can get an account flagged; keep auto-reply for selected chats.

## Build
GitHub Actions builds `GAZ-AI.apk` on every push (see `.github/workflows/build.yml`).
