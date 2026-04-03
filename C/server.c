#include <winsock2.h>
#include <windows.h>
#include <shlobj.h>
#include <shellapi.h>
#include <objbase.h>
#include <shobjidl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define START_PORT 8000
#define MAX_HEADERS 4096
#define WM_TRAYICON (WM_APP + 1)
#define ID_TRAY_EXIT 1001

char downloads_dir[MAX_PATH];
int active_port = START_PORT;
NOTIFYICONDATAA nid;

// --------------- 1. Installation & Uninstallation --------------- //

void handle_uninstall() {
    HKEY hKey;
    char szPathToExe[MAX_PATH];
    GetModuleFileNameA(NULL, szPathToExe, MAX_PATH);

    // 1. Remove from Windows Startup
    if (RegOpenKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_WRITE, &hKey) == ERROR_SUCCESS) {
        RegDeleteValueA(hKey, "RemoteControlAppServer");
        RegCloseKey(hKey);
    }

    // 2. Remove from Control Panel (Add/Remove Programs)
    RegDeleteKeyA(HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\RemoteControlAppServer");

    // 3. Delete the Desktop Shortcut
    char desktop_path[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_DESKTOPDIRECTORY, NULL, 0, desktop_path))) {
        char shortcut_path[MAX_PATH];
        snprintf(shortcut_path, MAX_PATH, "%s\\Remote Downloads.lnk", desktop_path);
        DeleteFileA(shortcut_path); // Silently deletes if it exists
    }

    // 4. Self-Delete trick (Spawns a hidden CMD that waits 2 seconds, then deletes this .exe)
    char cmd_args[MAX_PATH + 100];
    snprintf(cmd_args, sizeof(cmd_args), "/c ping 127.0.0.1 -n 3 > nul & del /f /q \"%s\"", szPathToExe);
    ShellExecuteA(NULL, "open", "cmd.exe", cmd_args, NULL, SW_HIDE);

    MessageBoxA(NULL, "Remote Control Server has been completely uninstalled from your system.", "Uninstall Successful", MB_OK | MB_ICONINFORMATION);
    ExitProcess(0);
}

void register_installation() {
    HKEY hKey, hUninst;
    char szPathToExe[MAX_PATH];
    GetModuleFileNameA(NULL, szPathToExe, MAX_PATH);

    // Register Startup
    if (RegOpenKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_WRITE, &hKey) == ERROR_SUCCESS) {
        RegSetValueExA(hKey, "RemoteControlAppServer", 0, REG_SZ, (unsigned char*)szPathToExe, strlen(szPathToExe) + 1);
        RegCloseKey(hKey);
    }

    // Register in Control Panel -> Add/Remove Programs
    if (RegCreateKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\RemoteControlAppServer", 0, NULL, REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &hUninst, NULL) == ERROR_SUCCESS) {
        char uninstallCmd[MAX_PATH + 20];
        snprintf(uninstallCmd, sizeof(uninstallCmd), "\"%s\" --uninstall", szPathToExe);
        
        RegSetValueExA(hUninst, "DisplayName", 0, REG_SZ, (unsigned char*)"Remote Control Server", 22);
        RegSetValueExA(hUninst, "Publisher", 0, REG_SZ, (unsigned char*)"Kuldeep Dilliwar", 17);
        RegSetValueExA(hUninst, "DisplayIcon", 0, REG_SZ, (unsigned char*)szPathToExe, strlen(szPathToExe) + 1);
        RegSetValueExA(hUninst, "UninstallString", 0, REG_SZ, (unsigned char*)uninstallCmd, strlen(uninstallCmd) + 1);
        RegCloseKey(hUninst);
    }
}

void create_desktop_shortcut() {
    char desktop_path[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_DESKTOPDIRECTORY, NULL, 0, desktop_path))) {
        char shortcut_path[MAX_PATH];
        snprintf(shortcut_path, MAX_PATH, "%s\\Remote Downloads.lnk", desktop_path);

        if (GetFileAttributesA(shortcut_path) != INVALID_FILE_ATTRIBUTES) return; 

        CoInitialize(NULL);
        IShellLinkA* psl;
        if (SUCCEEDED(CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkA, (LPVOID*)&psl))) {
            psl->lpVtbl->SetPath(psl, downloads_dir);
            psl->lpVtbl->SetDescription(psl, "Shortcut to Remote Control Downloads");
            IPersistFile* ppf;
            if (SUCCEEDED(psl->lpVtbl->QueryInterface(psl, &IID_IPersistFile, (LPVOID*)&ppf))) {
                WCHAR wsz[MAX_PATH];
                MultiByteToWideChar(CP_ACP, 0, shortcut_path, -1, wsz, MAX_PATH);
                ppf->lpVtbl->Save(ppf, wsz, TRUE);
                ppf->lpVtbl->Release(ppf);
            }
            psl->lpVtbl->Release(psl);
        }
        CoUninitialize();
    }
}

void setup_directory() {
    char appdata[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_LOCAL_APPDATA, NULL, 0, appdata))) {
        snprintf(downloads_dir, MAX_PATH, "%s\\RemoteControlApp", appdata);
        CreateDirectoryA(downloads_dir, NULL);
        snprintf(downloads_dir, MAX_PATH, "%s\\RemoteControlApp\\downloads", appdata);
        CreateDirectoryA(downloads_dir, NULL);
        create_desktop_shortcut();
    }
}

// --------------- 2. HTTP Helper Functions --------------- //

void send_response(SOCKET client, const char* status, const char* content_type, const char* body) {
    char response[2048];
    snprintf(response, sizeof(response),
             "HTTP/1.1 %s\r\n"
             "Content-Type: %s\r\n"
             "Connection: close\r\n"
             "Access-Control-Allow-Origin: *\r\n"
             "Content-Length: %zu\r\n\r\n"
             "%s", status, content_type, strlen(body), body);
    send(client, response, strlen(response), 0);
}

int read_exact(SOCKET client, char* buffer, int length) {
    int total_read = 0;
    while (total_read < length) {
        int bytes = recv(client, buffer + total_read, length - total_read, 0);
        if (bytes <= 0) return -1;
        total_read += bytes;
    }
    return total_read;
}

// --------------- 3. Multi-threaded Client Handler --------------- //

DWORD WINAPI handle_client_thread(LPVOID lpParam) {
    SOCKET client = (SOCKET)(ULONG_PTR)lpParam;
    char header_buf[MAX_HEADERS] = {0};
    int header_len = 0;
    char c;
    
    while (header_len < MAX_HEADERS - 1) {
        if (recv(client, &c, 1, 0) <= 0) break;
        header_buf[header_len++] = c;
        if (header_len >= 4 && strncmp(header_buf + header_len - 4, "\r\n\r\n", 4) == 0) break;
    }

    if (strncmp(header_buf, "GET /identify", 13) == 0) {
        char hostname[256];
        gethostname(hostname, sizeof(hostname));
        char json[512];
        snprintf(json, sizeof(json), "{\"app\": \"RemoteControlServer\", \"hostname\": \"%s\", \"port\": %d}", hostname, active_port);
        send_response(client, "200 OK", "application/json", json);
    }
    else if (strncmp(header_buf, "POST /open-folder", 17) == 0) {
        ShellExecuteA(NULL, "explore", downloads_dir, NULL, NULL, SW_SHOWDEFAULT);
        send_response(client, "200 OK", "application/json", "{\"message\": \"Downloads folder opened.\"}");
    }
    else if (strncmp(header_buf, "POST /upload-file", 17) == 0) {
        char* cl_ptr = strstr(header_buf, "Content-Length: ");
        if (!cl_ptr) {
            send_response(client, "411 Length Required", "application/json", "{\"error\": \"Missing Content-Length\"}");
            closesocket(client);
            return 0;
        }
        int content_length = atoi(cl_ptr + 16);
        
        char* body = (char*)malloc(content_length + 1);
        if (!body) {
            send_response(client, "500 Internal Server Error", "application/json", "{\"error\": \"Out of memory\"}");
            closesocket(client);
            return 0;
        }

        if (read_exact(client, body, content_length) < 0) {
            free(body);
            closesocket(client);
            return 0;
        }
        body[content_length] = '\0';

        char* filename_ptr = strstr(body, "filename=\"");
        if (filename_ptr) {
            filename_ptr += 10;
            char* filename_end = strchr(filename_ptr, '"');
            if (filename_end) {
                *filename_end = '\0'; 
                char* data_start = strstr(filename_end + 1, "\r\n\r\n");
                if (data_start) {
                    data_start += 4;
                    char* data_end = body + content_length - 4;
                    while (data_end > data_start && strncmp(data_end, "\r\n--", 4) != 0) data_end--;
                    if (data_end > data_start) {
                        int file_size = data_end - data_start;
                        char file_path[MAX_PATH];
                        snprintf(file_path, MAX_PATH, "%s\\%s", downloads_dir, filename_ptr);
                        
                        FILE* f = fopen(file_path, "wb");
                        if (f) {
                            fwrite(data_start, 1, file_size, f);
                            fclose(f);
                            send_response(client, "200 OK", "application/json", "{\"message\": \"File uploaded.\"}");
                        } else {
                            send_response(client, "500 Error", "application/json", "{\"error\": \"Disk write failed\"}");
                        }
                    }
                }
            }
        } else {
            send_response(client, "400 Bad Request", "application/json", "{\"error\": \"No file found\"}");
        }
        free(body);
    } else {
        send_response(client, "404 Not Found", "application/json", "{\"error\": \"Not found\"}");
    }

    closesocket(client);
    return 0;
}

// --------------- 4. Background Server Thread --------------- //

DWORD WINAPI server_thread_func(LPVOID lpParam) {
    WSADATA wsa;
    SOCKET server_fd, client_fd;
    struct sockaddr_in server, client;
    int c = sizeof(struct sockaddr_in);

    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 1;
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == INVALID_SOCKET) return 1;

    server.sin_family = AF_INET;
    server.sin_addr.s_addr = INADDR_ANY;
    
    while (1) {
        server.sin_port = htons(active_port);
        if (bind(server_fd, (struct sockaddr *)&server, sizeof(server)) != SOCKET_ERROR) break;
        active_port++; 
        if (active_port > START_PORT + 100) return 1; 
    }

    snprintf(nid.szTip, sizeof(nid.szTip), "Remote Server (Port: %d)", active_port);
    Shell_NotifyIconA(NIM_MODIFY, &nid);

    listen(server_fd, 3);

    while ((client_fd = accept(server_fd, (struct sockaddr *)&client, &c)) != INVALID_SOCKET) {
        HANDLE thread = CreateThread(NULL, 0, handle_client_thread, (LPVOID)(ULONG_PTR)client_fd, 0, NULL);
        if (thread) CloseHandle(thread); else closesocket(client_fd);
    }

    closesocket(server_fd);
    WSACleanup();
    return 0;
}

// --------------- 5. Windows GUI & Tray Icon --------------- //

LRESULT CALLBACK WindowProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam) {
    switch (uMsg) {
        case WM_TRAYICON:
            if (lParam == WM_RBUTTONUP) {
                POINT pt;
                GetCursorPos(&pt);
                HMENU hMenu = CreatePopupMenu();
                InsertMenuA(hMenu, 0, MF_BYPOSITION | MF_STRING, ID_TRAY_EXIT, "Exit Server");
                SetForegroundWindow(hwnd);
                int cmd = TrackPopupMenu(hMenu, TPM_RETURNCMD | TPM_NONOTIFY, pt.x, pt.y, 0, hwnd, NULL);
                DestroyMenu(hMenu);
                if (cmd == ID_TRAY_EXIT) {
                    Shell_NotifyIconA(NIM_DELETE, &nid);
                    PostQuitMessage(0);
                }
            }
            break;
        case WM_DESTROY:
            Shell_NotifyIconA(NIM_DELETE, &nid);
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProc(hwnd, uMsg, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    
    // Check if the user is trying to uninstall
    if (strstr(lpCmdLine, "--uninstall") != NULL) {
        handle_uninstall();
        return 0; // The program exits here during uninstall
    }

    // Check if another instance is already running
    HANDLE hMutex = CreateMutexA(NULL, TRUE, "RemoteControlServer_Unique_Mutex");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        CloseHandle(hMutex);
        return 0; // Quietly exit if already running
    }

    // Write metadata to registry for the Control Panel
    register_installation();
    setup_directory();

    WNDCLASSA wc = {0};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = "RemoteControlHiddenWindow";
    RegisterClassA(&wc);
    HWND hwnd = CreateWindowA(wc.lpszClassName, "RemoteControl", 0, 0, 0, 0, 0, HWND_MESSAGE, NULL, hInstance, NULL);

    nid.cbSize = sizeof(NOTIFYICONDATAA);
    nid.hWnd = hwnd;
    nid.uID = 1;
    nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    nid.uCallbackMessage = WM_TRAYICON;
    nid.hIcon = LoadIcon(NULL, IDI_INFORMATION); 
    snprintf(nid.szTip, sizeof(nid.szTip), "Remote Server (Starting...)");
    Shell_NotifyIconA(NIM_ADD, &nid);

    HANDLE server_thread = CreateThread(NULL, 0, server_thread_func, NULL, 0, NULL);
    CloseHandle(server_thread);

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    CloseHandle(hMutex);
    return 0;
}
