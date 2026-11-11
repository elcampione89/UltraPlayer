<?php
// Public endpoint the app reads on launch. Contains nothing secret.
declare(strict_types=1);
require __DIR__ . '/inc.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('Access-Control-Allow-Origin: *');

$d = load_data();

$messages = [];
foreach ($d['messages'] as $m) {
    if (!empty($m['active'])) {
        $messages[] = [
            'id' => (string)$m['id'],
            'title' => (string)$m['title'],
            'body' => (string)$m['body'],
            'once' => !empty($m['once']),
        ];
    }
}

$dns = [];
foreach ($d['dns'] as $s) {
    $dns[] = ['name' => (string)$s['name'], 'url' => (string)$s['url']];
}

echo json_encode([
    'app_name' => (string)$d['settings']['app_name'],
    'allow_custom_dns' => (bool)$d['settings']['allow_custom_dns'],
    'allow_m3u' => (bool)$d['settings']['allow_m3u'],
    'plex_enabled' => (bool)$d['settings']['plex_enabled'],
    'theme' => (string)$d['settings']['theme'],
    'allow_theme_change' => (bool)$d['settings']['allow_theme_change'],
    'dns' => $dns,
    'update' => [
        'version_code' => (int)$d['update']['version_code'],
        'version_name' => (string)$d['update']['version_name'],
        'url_arm32' => (string)$d['update']['url_arm32'],
        'url_arm64' => (string)$d['update']['url_arm64'],
        'url_universal' => (string)$d['update']['url_universal'],
        'changelog' => (string)$d['update']['changelog'],
        'force' => (bool)$d['update']['force'],
    ],
    'messages' => $messages,
    'categories' => [
        'top' => array_values(array_map('strval', $d['categories']['top'])),
        'bottom' => array_values(array_map('strval', $d['categories']['bottom'])),
        'hide' => array_values(array_map('strval', $d['categories']['hide'])),
        'hide_unlisted' => (bool)$d['categories']['hide_unlisted'],
        'sort' => $d['categories']['sort'] === 'az' ? 'az' : 'provider',
    ],
], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
