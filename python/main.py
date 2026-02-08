import sys
import os
import socket
import signal
import logging
import threading
import ctypes
import pathlib
from typing import Dict, Any, List
from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
import uvicorn
import pyautogui
import pyperclip
import pystray
from pystray import MenuItem as item
from PIL import Image, ImageDraw
from tkinter import Tk, Label
import screen_brightness_control as sbc

# pyinstaller --onefile  --hidden-import=python_multipart --hidden-import=pystray --noconsole --hidden-import=PIL server.py

# Fix for PyInstaller --no console
if sys.stdout is None:
    sys.stdout = open(os.devnull, 'w')

# Logging configuration
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
console_handler = logging.StreamHandler(sys.stdout)
console_handler.setLevel(logging.INFO)
formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)

# Imports for robust Windows volume control
if sys.platform == "win32":
    try:
        import comtypes
        from comtypes import CLSCTX_ALL
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

        WINDOWS_VOLUME_CONTROL_AVAILABLE = True
        logger.info("pycaw loaded for robust Windows volume control.")
    except ImportError:
        WINDOWS_VOLUME_CONTROL_AVAILABLE = False
        logger.warning("pycaw not found. Falling back to pyautogui for volume control.")
else:
    WINDOWS_VOLUME_CONTROL_AVAILABLE = False

# Mouse settings
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.0001

app = FastAPI(
    title="Remote Control API",
    description="API for remote mouse, keyboard, media, and system control.",
    version="1.14.0"  # Version updated for new features
)


# --------------- Helper Function for Volume Control --------------- #
def _handle_volume_control(key: str):
    """Handles volume changes using pycaw for reliability."""
    if not WINDOWS_VOLUME_CONTROL_AVAILABLE:
        pyautogui.press(key)
        logger.info(f"Using pyautogui fallback for volume key: {key}")
        return

    comtypes.CoInitialize()
    try:
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        volume = interface.QueryInterface(IAudioEndpointVolume)
        if key == 'volumeup':
            current_vol = volume.GetMasterVolumeLevelScalar()
            volume.SetMasterVolumeLevelScalar(min(1.0, current_vol + 0.05), None)
            logger.info(f"Volume up. New scalar: {min(1.0, current_vol + 0.05):.2f}")
        elif key == 'volumedown':
            current_vol = volume.GetMasterVolumeLevelScalar()
            volume.SetMasterVolumeLevelScalar(max(0.0, current_vol - 0.05), None)
            logger.info(f"Volume down. New scalar: {max(0.0, current_vol - 0.05):.2f}")
        elif key == 'volumemute':
            is_muted = volume.GetMute()
            volume.SetMute(not is_muted, None)
            logger.info(f"Volume mute toggled to: {not is_muted}")
    finally:
        comtypes.CoUninitialize()


# --------------- Download Directory Setup (Safe) --------------- #
base_dir = pathlib.Path(os.getenv('LOCALAPPDATA', os.getcwd())) / "RemoteControlApp"
downloads_dir = base_dir / "downloads"
os.makedirs(downloads_dir, exist_ok=True)
logger.info(f"Download folder set to: {downloads_dir}")


# ---------------------- API MODELS ---------------------- #
class CharacterRequest(BaseModel): char: str


class MouseMoveRequest(BaseModel): dx: float; dy: float; sensitivity: float


class ClickRequest(BaseModel): button: str


class ScrollRequest(BaseModel): dy: float


class HScrollGestureRequest(BaseModel): dx: float; state: str


class KeyPressRequest(BaseModel): key: str


class MediaKeyRequest(BaseModel): key: str


class IdentifyResponse(BaseModel): app: str; hostname: str


class CommandRequest(BaseModel): type: str; payload: Dict[str, Any]


# ---------------------- API ENDPOINTS ---------------------- #
@app.get("/identify", response_model=IdentifyResponse)
async def identify_server():
    try:
        hostname = socket.gethostname()
        return {"app": "RemoteControlServer", "hostname": hostname}
    except Exception as e:
        logger.error(f"Error in identify_server: {e}")
        raise HTTPException(500, "Could not get server identification.")


# --- Legacy Endpoints (Maintained for older app versions) ---
@app.post("/move-mouse")
async def move_mouse(data: MouseMoveRequest):
    try:
        pyautogui.moveRel(data.dx * data.sensitivity, data.dy * data.sensitivity, duration=0)
        return {"message": "Mouse moved."}
    except Exception as e:
        logger.error(f"Error in move_mouse: {e}");
        raise HTTPException(500)


@app.post("/click-mouse")
async def click_mouse(data: ClickRequest):
    try:
        pyautogui.click(button=data.button)
        return {"message": f"{data.button} click performed."}
    except Exception as e:
        logger.error(f"Error in click_mouse: {e}");
        raise HTTPException(500)


@app.post("/scroll-mouse")
async def scroll_mouse(data: ScrollRequest):
    try:
        pyautogui.scroll(int(data.dy))
        return {"message": "Scrolled."}
    except Exception as e:
        logger.error(f"Error in scroll_mouse: {e}");
        raise HTTPException(500)


@app.post("/send-char")
async def receive_character(data: CharacterRequest):
    try:
        pyperclip.copy(data.char)
        pyautogui.hotkey("ctrl", "v")
        return {"message": f"Pasted: '{data.char}'"}
    except Exception as e:
        logger.error(f"Error in receive_character: {e}");
        raise HTTPException(500)


@app.post("/press-key")
async def press_key(data: KeyPressRequest):
    key = data.key.lower()
    valid_keys = ["esc", "enter", "f11", "backspace", "up", "down", "left", "right", "pageup", "pagedown"]
    if key not in valid_keys: raise HTTPException(400, f"Invalid key: {key}")
    try:
        pyautogui.press(key)
        return {"message": f"Key '{key}' pressed."}
    except Exception as e:
        logger.error(f"Error in press_key: {e}");
        raise HTTPException(500)


@app.post("/press-media-key")
async def press_media_key(data: MediaKeyRequest):
    key = data.key.lower()
    valid_keys = ['playpause', 'nexttrack', 'prevtrack', 'volumeup', 'volumedown', 'volumemute']
    if key not in valid_keys: raise HTTPException(400, f"Invalid media key: {key}")
    try:
        if key in ['volumeup', 'volumedown', 'volumemute']:
            _handle_volume_control(key)
        else:
            pyautogui.press(key)
        return {"message": f"Media key '{key}' pressed."}
    except Exception as e:
        logger.error(f"Error in press_media_key: {e}");
        raise HTTPException(500)


@app.post("/upload-file")
async def upload_file(file: UploadFile = File(...)):
    try:
        file_location = downloads_dir / file.filename
        with open(file_location, "wb", buffering=8192) as f:
            while chunk := await file.read(8192):
                f.write(chunk)
        return {"message": f"File '{file.filename}' uploaded."}
    except Exception as e:
        logger.error(f"Error in upload_file: {e}");
        raise HTTPException(500)


@app.post("/press-hotkey")
async def press_hotkey(data: KeyPressRequest):
    hotkey = data.key.lower()
    try:
        keys = hotkey.split('+')
        pyautogui.hotkey(*keys)
        return {"message": f"Hotkey '{hotkey}' pressed."}
    except Exception as e:
        logger.error(f"Error in press_hotkey: {e}");
        raise HTTPException(500)


@app.post("/hscroll-gesture")
async def hscroll_gesture(data: HScrollGestureRequest):
    try:
        if data.state == "start":
            pyautogui.keyDown('shift'); return {"message": "H-scroll started."}
        elif data.state == "drag":
            pyautogui.scroll(int(-data.dx)); return {"message": "H-scrolling."}
        elif data.state == "end":
            pyautogui.keyUp('shift'); return {"message": "H-scroll ended."}
        else:
            raise HTTPException(400, "Invalid gesture state.")
    except Exception as e:
        logger.error(f"Error in hscroll_gesture: {e}");
        pyautogui.keyUp('shift');
        raise HTTPException(500)


@app.post("/open-folder")
async def open_folder():
    try:
        if sys.platform == "win32":
            os.startfile(str(downloads_dir))
        elif sys.platform == "darwin":
            os.system(f'open "{str(downloads_dir)}"')
        else:
            os.system(f'xdg-open "{str(downloads_dir)}"')
        return {"message": "Downloads folder opened."}
    except Exception as e:
        logger.error(f"Error in open_folder: {e}");
        raise HTTPException(500)


# --- NEW: Generic Command Executor Endpoint ---
@app.post("/execute-command")
async def execute_command(command: CommandRequest):
    cmd_type = command.type
    payload = command.payload
    logger.info(f"Executing: Type='{cmd_type}', Payload='{payload}'")

    try:
        if cmd_type == 'key_press':
            key = payload.get('key')
            if not key: raise HTTPException(400, "Key not specified.")
            if key in ['volumeup', 'volumedown', 'volumemute']:
                _handle_volume_control(key)
            else:
                pyautogui.press(key)

        elif cmd_type == 'hotkey':
            keys = payload.get('keys', [])
            if keys: pyautogui.hotkey(*keys)

        elif cmd_type == 'mouse_click':
            button = payload.get('button')
            if not button or button not in ['left', 'middle', 'right']: raise HTTPException(400,
                                                                                            "Invalid mouse button.")
            pyautogui.click(button=button)

        elif cmd_type == 'open_folder':
            if sys.platform == "win32":
                os.startfile(str(downloads_dir))
            elif sys.platform == "darwin":
                os.system(f'open "{str(downloads_dir)}"')
            else:
                os.system(f'xdg-open "{str(downloads_dir)}"')

        elif cmd_type == 'brightness_control':
            change = payload.get('change')
            if not isinstance(change, int): raise HTTPException(400, "Invalid brightness value.")
            current = sbc.get_brightness(display=0)[0]
            sbc.set_brightness(max(0, min(100, current + change)), display=0)

        # --- NEW HANDLERS FOR GESTURE WIDGETS ---
        elif cmd_type == 'mouse_move':
            dx = payload.get('dx', 0.0)
            dy = payload.get('dy', 0.0)
            pyautogui.moveRel(dx, dy, duration=0)

        elif cmd_type == 'v_scroll':
            dy = payload.get('dy', 0.0)
            pyautogui.scroll(int(dy))

        elif cmd_type == 'h_scroll':
            state = payload.get('state')
            dx = payload.get('dx', 0.0)
            if state == "start":
                pyautogui.keyDown('shift')
            elif state == "drag":
                pyautogui.scroll(int(-dx))
            elif state == "end":
                pyautogui.keyUp('shift')

        else:
            logger.warning(f"Unknown command type: {cmd_type}")
            raise HTTPException(400, f"Unknown command type: {cmd_type}")

        return {"status": "command executed", "command": command.model_dump()}

    except Exception as e:
        logger.error(f"Error executing command: {e}")
        # Always release shift key on error during h-scroll
        if cmd_type == 'h_scroll': pyautogui.keyUp('shift')
        raise HTTPException(500, f"Error executing command: {str(e)}")


# --- System Tray and Server Runner ---
def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s: return s.connect_ex(('0.0.0.0', port)) == 0


def handle_shutdown(signum, frame):
    logger.info(f"Shutdown signal ({signum}) received. Exiting.");
    sys.exit(0)


signal.signal(signal.SIGINT, handle_shutdown);
signal.signal(signal.SIGTERM, handle_shutdown)
if sys.platform == "win32":
    def console_ctrl_handler(ctrl_type):
        if ctrl_type == 2: ctypes.windll.user32.ShowWindow(ctypes.windll.kernel32.GetConsoleWindow(), 6); logger.info(
            "Console window close event. Minimizing."); return True
        return False


    ctrl_handler_func = ctypes.WINFUNCTYPE(ctypes.wintypes.BOOL, ctypes.wintypes.DWORD)(console_ctrl_handler)
    ctypes.windll.kernel32.SetConsoleCtrlHandler(ctrl_handler_func, True)
show_ip_flag = False;
ip_window = None


def get_ip_address():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80)); return s.getsockname()[0]
    except:
        return "127.0.0.1"


def on_window_close(window): global show_ip_flag, ip_window; show_ip_flag = False; ip_window = None; window.destroy()


def toggle_ip_window(icon, item):
    global show_ip_flag, ip_window
    show_ip_flag = not show_ip_flag
    if show_ip_flag and ip_window is None:
        def run_ip_window():
            global ip_window;
            ip_window = Tk();
            ip_window.title("System IP");
            ip_window.attributes("-topmost", True);
            Label(ip_window, text=f"Your IP: {get_ip_address()}", font=("Arial", 14)).pack(padx=20, pady=20);
            ip_window.protocol("WM_DELETE_WINDOW", lambda: on_window_close(ip_window));
            ip_window.mainloop()

        threading.Thread(target=run_ip_window, daemon=True).start()
    elif ip_window:
        ip_window.destroy(); ip_window = None


def tray_quit(icon, item): logger.info("Quitting from tray."); icon.stop(); os._exit(0)


def create_image(): image = Image.new('RGB', (64, 64), "white"); dc = ImageDraw.Draw(image); dc.rectangle(
    (8, 8, 56, 56), fill="blue"); return image


def setup_tray(): menu = (item('Show IP Address', toggle_ip_window, checked=lambda i: show_ip_flag),
                          item('Quit', tray_quit)); icon = pystray.Icon("RemoteControl", create_image(),
                                                                        "Remote Control App", menu); icon.run()


def run_server():
    port = 8000
    if is_port_in_use(port): logger.error(f"Port {port} in use."); sys.exit(1)
    try:
        uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")
    except Exception as e:
        logger.error(f"Server startup failed: {e}"); sys.exit(1)


if __name__ == "__main__": threading.Thread(target=run_server, daemon=True).start(); setup_tray()

# pyinstaller --onefile  --hidden-import=python_multipart --hidden-import=pystray --noconsole --hidden-import=PIL server.py
