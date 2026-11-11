<?php
// Ultra Player panel - shared helpers. Storage is plain JSON in ./data (no database needed).
declare(strict_types=1);

const DATA_DIR = __DIR__ . '/data';
const UPLOAD_DIR = __DIR__ . '/uploads';
const DATA_FILE = DATA_DIR . '/panel.json';
const AUTH_FILE = DATA_DIR . '/auth.json';

function ensure_dirs(): void {
    foreach ([DATA_DIR, UPLOAD_DIR] as $d) {
        if (!is_dir($d)) { @mkdir($d, 0755, true); }
    }
    // Block web access to raw data files
    if (!file_exists(DATA_DIR . '/.htaccess')) {
        @file_put_contents(DATA_DIR . '/.htaccess', "Require all denied\nDeny from all\n");
    }
    // Uploaded files are served as downloads only, never executed
    if (!file_exists(UPLOAD_DIR . '/.htaccess')) {
        @file_put_contents(UPLOAD_DIR . '/.htaccess',
            "Options -Indexes -ExecCGI\nRemoveHandler .php .phtml .php3 .php4 .php5 .php7 .php8\n" .
            "<FilesMatch \"\\.(php|phtml|phar|pl|py|cgi|sh)$\">\nRequire all denied\n</FilesMatch>\n" .
            "AddType application/vnd.android.package-archive .apk\n");
    }
}

function default_data(): array {
    return [
        'settings' => [
            'app_name' => 'Ultra Player',
            'allow_custom_dns' => true,
            'allow_m3u' => true,
            'plex_enabled' => true,
            'theme' => 'purple',          // Ultra Player default look
            'allow_theme_change' => true, // let users pick their own in Settings
        ],
        'dns' => [],          // [{id, name, url}]
        'update' => [
            'version_code' => 0,
            'version_name' => '',
            'url_arm32' => '',
            'url_arm64' => '',
            'url_universal' => '',
            'changelog' => '',
            'force' => false,
        ],
        'messages' => [],     // [{id, title, body, active, once, created}]
        'categories' => [
            'top' => [],          // patterns, in order, shown first
            'bottom' => ['24/7'], // patterns, in order, shown last
            'hide' => [],         // patterns never shown
            'hide_unlisted' => false,
            'sort' => 'provider', // provider | az (order inside each group)
        ],
    ];
}

function load_data(): array {
    ensure_dirs();
    if (!file_exists(DATA_FILE)) { return default_data(); }
    $raw = file_get_contents(DATA_FILE);
    $d = json_decode($raw ?: '', true);
    if (!is_array($d)) { return default_data(); }
    // merge in any keys added in newer versions (shallow per section, so saved lists - even empty ones - win)
    $out = default_data();
    foreach ($d as $k => $v) {
        if (isset($out[$k]) && is_array($out[$k]) && is_array($v) && array_keys($out[$k]) !== range(0, count($out[$k]) - 1)) {
            $out[$k] = array_merge($out[$k], $v);
        } else {
            $out[$k] = $v;
        }
    }
    return $out;
}

function save_data(array $d): void {
    ensure_dirs();
    $tmp = DATA_FILE . '.tmp';
    file_put_contents($tmp, json_encode($d, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), LOCK_EX);
    rename($tmp, DATA_FILE);
}

function load_auth(): ?array {
    if (!file_exists(AUTH_FILE)) { return null; }
    $a = json_decode((string)file_get_contents(AUTH_FILE), true);
    return is_array($a) ? $a : null;
}

function save_auth(string $user, string $pass): void {
    ensure_dirs();
    file_put_contents(AUTH_FILE, json_encode([
        'user' => $user,
        'hash' => password_hash($pass, PASSWORD_DEFAULT),
    ]), LOCK_EX);
}

function lines_to_list(string $txt): array {
    $out = [];
    foreach (preg_split('/\r\n|\r|\n/', $txt) as $l) {
        $l = trim($l);
        if ($l !== '' && !in_array($l, $out, true)) { $out[] = $l; }
    }
    return $out;
}

function h(?string $s): string { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

function new_id(): string { return bin2hex(random_bytes(6)); }

function base_url(): string {
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $dir = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
    return ($https ? 'https' : 'http') . '://' . $host . $dir . '/';
}

function normalise_url(string $u): string {
    $u = trim($u);
    if ($u === '') { return ''; }
    if (!preg_match('#^https?://#i', $u)) { $u = 'http://' . $u; }
    return rtrim($u, '/');
}
