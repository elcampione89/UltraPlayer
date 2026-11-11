<?php
// Ultra Player panel - admin dashboard
declare(strict_types=1);
require __DIR__ . '/inc.php';

session_name('mtvpanel');
session_set_cookie_params(['httponly' => true, 'samesite' => 'Lax', 'secure' => !empty($_SERVER['HTTPS'])]);
session_start();
ensure_dirs();

if (empty($_SESSION['csrf'])) { $_SESSION['csrf'] = bin2hex(random_bytes(16)); }
$csrf = $_SESSION['csrf'];
$auth = load_auth();
$flash = '';
$error = '';

function check_csrf(): void {
    if (!hash_equals($_SESSION['csrf'] ?? '', (string)($_POST['csrf'] ?? ''))) {
        http_response_code(400);
        exit('Bad request (session expired). Go back and refresh.');
    }
}

$action = $_POST['action'] ?? '';

// ---------- first-run setup ----------
if ($auth === null) {
    if ($action === 'setup') {
        check_csrf();
        $u = trim((string)($_POST['user'] ?? ''));
        $p = (string)($_POST['pass'] ?? '');
        if ($u === '' || strlen($p) < 6) {
            $error = 'Username required and password must be at least 6 characters.';
        } else {
            save_auth($u, $p);
            if (!file_exists(DATA_FILE)) { save_data(default_data()); }
            $_SESSION['admin'] = $u;
            header('Location: index.php');
            exit;
        }
    }
    render_header('Set up panel');
    ?>
    <div class="card narrow">
        <h2>First-time setup</h2>
        <p class="muted">Create the admin login for this panel.</p>
        <?php if ($error) echo '<div class="err">' . h($error) . '</div>'; ?>
        <form method="post">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="setup">
            <label>Username<input name="user" required autocomplete="username"></label>
            <label>Password<input type="password" name="pass" required minlength="6" autocomplete="new-password"></label>
            <button>Create admin</button>
        </form>
    </div>
    <?php
    render_footer();
    exit;
}

// ---------- login / logout ----------
if ($action === 'login') {
    check_csrf();
    $u = (string)($_POST['user'] ?? '');
    $p = (string)($_POST['pass'] ?? '');
    usleep(300000);
    if (hash_equals($auth['user'], $u) && password_verify($p, $auth['hash'])) {
        session_regenerate_id(true);
        $_SESSION['admin'] = $u;
        header('Location: index.php');
        exit;
    }
    $error = 'Wrong username or password.';
}
if (($_GET['logout'] ?? '') === '1') {
    session_destroy();
    header('Location: index.php');
    exit;
}
if (empty($_SESSION['admin'])) {
    render_header('Login');
    ?>
    <div class="card narrow">
        <h2>Panel login</h2>
        <?php if ($error) echo '<div class="err">' . h($error) . '</div>'; ?>
        <form method="post">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="login">
            <label>Username<input name="user" required autocomplete="username"></label>
            <label>Password<input type="password" name="pass" required autocomplete="current-password"></label>
            <button>Log in</button>
        </form>
    </div>
    <?php
    render_footer();
    exit;
}

// ---------- authenticated actions ----------
$d = load_data();

if ($action !== '') {
    check_csrf();
    switch ($action) {
        case 'dns_add':
            $name = trim((string)($_POST['name'] ?? ''));
            $url = normalise_url((string)($_POST['url'] ?? ''));
            if ($name === '' || $url === '') { $error = 'Name and URL are required.'; break; }
            $d['dns'][] = ['id' => new_id(), 'name' => $name, 'url' => $url];
            save_data($d); $flash = 'Server added.';
            break;
        case 'dns_delete':
            $id = (string)($_POST['id'] ?? '');
            $d['dns'] = array_values(array_filter($d['dns'], fn($s) => $s['id'] !== $id));
            save_data($d); $flash = 'Server removed.';
            break;
        case 'dns_move':
            $id = (string)($_POST['id'] ?? '');
            $dir = ($_POST['dir'] ?? '') === 'up' ? -1 : 1;
            foreach ($d['dns'] as $i => $s) {
                if ($s['id'] === $id) {
                    $j = $i + $dir;
                    if ($j >= 0 && $j < count($d['dns'])) {
                        [$d['dns'][$i], $d['dns'][$j]] = [$d['dns'][$j], $d['dns'][$i]];
                        save_data($d);
                    }
                    break;
                }
            }
            break;
        case 'update_save':
            $u = &$d['update'];
            $u['version_code'] = max(0, (int)($_POST['version_code'] ?? 0));
            $u['version_name'] = trim((string)($_POST['version_name'] ?? ''));
            $u['changelog'] = trim((string)($_POST['changelog'] ?? ''));
            $u['force'] = !empty($_POST['force']);
            foreach (['arm32', 'arm64', 'universal'] as $abi) {
                $u['url_' . $abi] = trim((string)($_POST['url_' . $abi] ?? ''));
                $f = $_FILES['apk_' . $abi] ?? null;
                if ($f && ($f['error'] ?? UPLOAD_ERR_NO_FILE) === UPLOAD_ERR_OK) {
                    $head = (string)file_get_contents($f['tmp_name'], false, null, 0, 4);
                    if (substr($head, 0, 2) !== 'PK') { $error = "The $abi file isn't an APK."; continue; }
                    $safeVer = preg_replace('/[^A-Za-z0-9._-]/', '', $u['version_name'] ?: (string)$u['version_code']);
                    $name = 'UltraPlayer-' . $safeVer . '-' . $abi . '.apk';
                    if (move_uploaded_file($f['tmp_name'], UPLOAD_DIR . '/' . $name)) {
                        $u['url_' . $abi] = base_url() . 'uploads/' . rawurlencode($name);
                    } else {
                        $error = "Couldn't save the $abi upload (check folder permissions).";
                    }
                } elseif ($f && ($f['error'] ?? 0) === UPLOAD_ERR_INI_SIZE) {
                    $error = "The $abi APK is bigger than PHP's upload_max_filesize (" . ini_get('upload_max_filesize') . "). Raise it in cPanel > MultiPHP INI Editor, or upload via File Manager and paste the link.";
                }
            }
            unset($u);
            save_data($d);
            if (!$error) { $flash = 'Update saved. Apps below version code ' . $d['update']['version_code'] . ' will be prompted.'; }
            break;
        case 'msg_add':
            $title = trim((string)($_POST['title'] ?? ''));
            $body = trim((string)($_POST['body'] ?? ''));
            if ($body === '') { $error = 'Message text is required.'; break; }
            array_unshift($d['messages'], [
                'id' => new_id(), 'title' => $title, 'body' => $body,
                'active' => true, 'once' => !empty($_POST['once']), 'created' => time(),
            ]);
            save_data($d); $flash = 'Message posted.';
            break;
        case 'msg_toggle':
            $id = (string)($_POST['id'] ?? '');
            foreach ($d['messages'] as &$m) { if ($m['id'] === $id) { $m['active'] = empty($m['active']); } }
            unset($m);
            save_data($d);
            break;
        case 'msg_delete':
            $id = (string)($_POST['id'] ?? '');
            $d['messages'] = array_values(array_filter($d['messages'], fn($m) => $m['id'] !== $id));
            save_data($d); $flash = 'Message deleted.';
            break;
        case 'settings_save':
            $d['settings']['app_name'] = trim((string)($_POST['app_name'] ?? '')) ?: 'Ultra Player';
            $d['settings']['allow_custom_dns'] = !empty($_POST['allow_custom_dns']);
            $d['settings']['allow_m3u'] = !empty($_POST['allow_m3u']);
            $d['settings']['plex_enabled'] = !empty($_POST['plex_enabled']);
            save_data($d); $flash = 'Settings saved.';
            break;
        case 'categories_save':
            $d['categories']['top'] = lines_to_list((string)($_POST['top'] ?? ''));
            $d['categories']['bottom'] = lines_to_list((string)($_POST['bottom'] ?? ''));
            $d['categories']['hide'] = lines_to_list((string)($_POST['hide'] ?? ''));
            $d['categories']['hide_unlisted'] = !empty($_POST['hide_unlisted']);
            $d['categories']['sort'] = ($_POST['sort'] ?? '') === 'az' ? 'az' : 'provider';
            save_data($d); $flash = 'Category rules saved. Apps pick them up next time they open.';
            break;
        case 'password_change':
            $p = (string)($_POST['pass'] ?? '');
            if (strlen($p) < 6) { $error = 'Password must be at least 6 characters.'; break; }
            save_auth($auth['user'], $p); $flash = 'Password changed.';
            break;
    }
}

$tab = $_GET['tab'] ?? 'servers';
$tabs = ['servers' => 'Servers', 'categories' => 'Categories', 'update' => 'App update', 'messages' => 'Messages', 'settings' => 'Settings'];
if (!isset($tabs[$tab])) { $tab = 'servers'; }

render_header($tabs[$tab]);
?>
<nav>
    <div class="brand"><?= h($d['settings']['app_name']) ?> <span>panel</span></div>
    <?php foreach ($tabs as $k => $label): ?>
        <a href="?tab=<?= $k ?>" class="<?= $k === $tab ? 'on' : '' ?>"><?= h($label) ?></a>
    <?php endforeach; ?>
    <a href="?logout=1" class="logout">Log out</a>
</nav>
<main>
<?php if ($flash) echo '<div class="ok">' . h($flash) . '</div>'; ?>
<?php if ($error) echo '<div class="err">' . h($error) . '</div>'; ?>

<?php if ($tab === 'servers'): ?>
    <div class="card">
        <h2>Servers (DNS)</h2>
        <p class="muted">These show as a dropdown on the app's login screen, so users only type their username and password.</p>
        <?php if (!$d['dns']): ?><p class="muted"><em>No servers yet.</em></p><?php endif; ?>
        <table>
            <?php foreach ($d['dns'] as $i => $s): ?>
            <tr>
                <td><strong><?= h($s['name']) ?></strong><br><span class="muted"><?= h($s['url']) ?></span></td>
                <td class="actions">
                    <?php foreach (['up' => '↑', 'down' => '↓'] as $dir => $ico): ?>
                    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="dns_move"><input type="hidden" name="dir" value="<?= $dir ?>"><input type="hidden" name="id" value="<?= h($s['id']) ?>"><button class="ghost"><?= $ico ?></button></form>
                    <?php endforeach; ?>
                    <form method="post" onsubmit="return confirm('Remove this server?')"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="dns_delete"><input type="hidden" name="id" value="<?= h($s['id']) ?>"><button class="danger">Remove</button></form>
                </td>
            </tr>
            <?php endforeach; ?>
        </table>
    </div>
    <div class="card">
        <h3>Add server</h3>
        <form method="post" class="row">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="dns_add">
            <label>Name<input name="name" placeholder="Main server" required></label>
            <label>URL<input name="url" placeholder="http://line.example.com:8080" required></label>
            <button>Add</button>
        </form>
    </div>

<?php elseif ($tab === 'categories'): $c = $d['categories']; ?>
    <div class="card">
        <h2>Category order</h2>
        <p class="muted">One rule per line. Each rule matches any category <b>containing</b> that text (case doesn't matter).
            Start a rule with <code>^</code> to match only names that <b>start</b> with it. For example, <code>^UK</code> catches "UK | Sports" but not "UKRAINE".
            Rules apply to Live, Movies and Series on every device.</p>
        <form method="post">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="categories_save">
            <label>Show first, in this order
                <textarea name="top" rows="8" placeholder="^UK&#10;UK SPORTS&#10;^UK| ENTERTAINMENT&#10;NEWS"><?= h(implode("\n", $c['top'])) ?></textarea></label>
            <label>Show last, in this order
                <textarea name="bottom" rows="4" placeholder="24/7"><?= h(implode("\n", $c['bottom'])) ?></textarea></label>
            <label>Hide completely
                <textarea name="hide" rows="5" placeholder="ADULT&#10;^AR&#10;^TR"><?= h(implode("\n", $c['hide'])) ?></textarea></label>
            <label class="check"><input type="checkbox" name="hide_unlisted" value="1" <?= $c['hide_unlisted'] ? 'checked' : '' ?>> Hide every category that isn't in the "first" or "last" lists</label>
            <label>Everything in between
                <select name="sort" style="display:block;width:100%;margin-top:4px;padding:11px 12px;background:#14121a;border:1px solid var(--line);border-radius:12px;color:var(--txt);font:inherit">
                    <option value="provider" <?= $c['sort'] !== 'az' ? 'selected' : '' ?>>Keep the provider's order</option>
                    <option value="az" <?= $c['sort'] === 'az' ? 'selected' : '' ?>>Sort A–Z</option>
                </select></label>
            <button>Save rules</button>
        </form>
    </div>

<?php elseif ($tab === 'update'): $u = $d['update']; ?>
    <div class="card">
        <h2>App update</h2>
        <p class="muted">When an app's version code is lower than the one here, it shows the update pop-up on launch. Firesticks use the <b>arm32</b> APK.
            Your PHP upload limit is <b><?= h(ini_get('upload_max_filesize')) ?></b>. If the APK is bigger, upload it with File Manager and paste the link.</p>
        <form method="post" enctype="multipart/form-data">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="update_save">
            <div class="row">
                <label>Version code<input type="number" name="version_code" min="0" value="<?= (int)$u['version_code'] ?>" required></label>
                <label>Version name<input name="version_name" value="<?= h($u['version_name']) ?>" placeholder="3.2-p2"></label>
            </div>
            <?php foreach (['arm32' => 'arm32 (Firestick / armeabi-v7a)', 'arm64' => 'arm64 (phones, newer boxes)', 'universal' => 'Universal (fallback)'] as $abi => $label): ?>
            <fieldset>
                <legend><?= h($label) ?></legend>
                <label>Direct link<input name="url_<?= $abi ?>" value="<?= h($u['url_' . $abi]) ?>" placeholder="https://.../UltraPlayer-<?= $abi ?>.apk"></label>
                <label class="file">…or upload APK<input type="file" name="apk_<?= $abi ?>" accept=".apk"></label>
            </fieldset>
            <?php endforeach; ?>
            <label>What's new<textarea name="changelog" rows="5"><?= h($u['changelog']) ?></textarea></label>
            <label class="check"><input type="checkbox" name="force" value="1" <?= $u['force'] ? 'checked' : '' ?>> Force update (users can't skip it)</label>
            <button>Save update</button>
        </form>
    </div>

<?php elseif ($tab === 'messages'): ?>
    <div class="card">
        <h3>New message</h3>
        <form method="post">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="msg_add">
            <label>Title<input name="title" placeholder="Heads up"></label>
            <label>Message<textarea name="body" rows="4" required></textarea></label>
            <label class="check"><input type="checkbox" name="once" value="1" checked> Show once per device</label>
            <button>Post message</button>
        </form>
    </div>
    <div class="card">
        <h2>Messages</h2>
        <?php if (!$d['messages']): ?><p class="muted"><em>No messages.</em></p><?php endif; ?>
        <table>
            <?php foreach ($d['messages'] as $m): ?>
            <tr class="<?= empty($m['active']) ? 'dim' : '' ?>">
                <td><strong><?= h($m['title'] ?: '(no title)') ?></strong>
                    <span class="pill"><?= empty($m['active']) ? 'hidden' : 'live' ?></span>
                    <?php if (!empty($m['once'])): ?><span class="pill">once</span><?php endif; ?>
                    <br><span class="muted"><?= nl2br(h($m['body'])) ?></span>
                    <br><small class="muted"><?= date('j M Y H:i', (int)($m['created'] ?? 0)) ?></small></td>
                <td class="actions">
                    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="msg_toggle"><input type="hidden" name="id" value="<?= h($m['id']) ?>"><button class="ghost"><?= empty($m['active']) ? 'Show' : 'Hide' ?></button></form>
                    <form method="post" onsubmit="return confirm('Delete this message?')"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="msg_delete"><input type="hidden" name="id" value="<?= h($m['id']) ?>"><button class="danger">Delete</button></form>
                </td>
            </tr>
            <?php endforeach; ?>
        </table>
    </div>

<?php else: $s = $d['settings']; ?>
    <div class="card">
        <h2>Settings</h2>
        <form method="post">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="settings_save">
            <label>Name shown in the panel and app pop-ups<input name="app_name" value="<?= h($s['app_name']) ?>"></label>
            <label class="check"><input type="checkbox" name="allow_custom_dns" value="1" <?= $s['allow_custom_dns'] ? 'checked' : '' ?>> Let users type their own server URL</label>
            <label class="check"><input type="checkbox" name="allow_m3u" value="1" <?= $s['allow_m3u'] ? 'checked' : '' ?>> Allow M3U playlist login</label>
            <label class="check"><input type="checkbox" name="plex_enabled" value="1" <?= $s['plex_enabled'] ? 'checked' : '' ?>> Show the Plex tab</label>
            <button>Save settings</button>
        </form>
    </div>
    <div class="card">
        <h3>App endpoint</h3>
        <p class="muted">The app reads this URL. It's baked in at build time as <code>PANEL_URL</code>.</p>
        <code class="block"><?= h(base_url()) ?>api.php</code>
    </div>
    <div class="card">
        <h3>Change password</h3>
        <form method="post" class="row">
            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
            <input type="hidden" name="action" value="password_change">
            <label>New password<input type="password" name="pass" minlength="6" required autocomplete="new-password"></label>
            <button>Change</button>
        </form>
    </div>
<?php endif; ?>
</main>
<?php
render_footer();

// ---------- layout ----------
function render_header(string $title): void { ?>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title><?= h($title) ?> · Ultra Player panel</title>
<style>
:root{--bg:#121016;--card:#1d1a23;--line:#2e2938;--txt:#ece6f0;--mut:#a59fb0;--pri:#cfbcff;--pri2:#381e72;--ok:#1f3b2a;--err:#4a1d1d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
nav{display:flex;flex-wrap:wrap;gap:4px;align-items:center;padding:12px 16px;background:var(--card);border-bottom:1px solid var(--line);position:sticky;top:0}
nav .brand{font-weight:800;margin-right:12px}nav .brand span{color:var(--pri)}
nav a{color:var(--mut);text-decoration:none;padding:8px 14px;border-radius:999px}nav a.on{background:var(--pri2);color:var(--pri)}nav a.logout{margin-left:auto}
main{max-width:860px;margin:0 auto;padding:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:20px;margin:16px 0}.card.narrow{max-width:380px;margin:12vh auto}
h2,h3{margin:0 0 8px}.muted{color:var(--mut)}label{display:block;margin:10px 0;color:var(--mut);font-size:13px}
input,textarea{display:block;width:100%;margin-top:4px;padding:11px 12px;background:#14121a;border:1px solid var(--line);border-radius:12px;color:var(--txt);font:inherit}
input:focus,textarea:focus{outline:2px solid var(--pri);border-color:transparent}
label.check{display:flex;gap:10px;align-items:center;font-size:15px;color:var(--txt)}label.check input{width:auto;margin:0}
button{background:var(--pri);color:var(--pri2);border:0;border-radius:999px;padding:10px 20px;font:600 14px system-ui;cursor:pointer;margin-top:6px}
button.ghost{background:transparent;color:var(--pri);border:1px solid var(--line)}button.danger{background:transparent;color:#ffb4ab;border:1px solid #5c2b2b}
.row{display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end}.row label{flex:1;min-width:180px}
fieldset{border:1px solid var(--line);border-radius:14px;margin:12px 0;padding:4px 14px 8px}legend{color:var(--pri);padding:0 6px;font-size:13px}
table{width:100%;border-collapse:collapse}td{padding:12px 4px;border-top:1px solid var(--line);vertical-align:top}tr.dim{opacity:.5}
td.actions{white-space:nowrap;text-align:right}td.actions form{display:inline}
.ok,.err{padding:12px 16px;border-radius:14px;margin:12px 0}.ok{background:var(--ok)}.err{background:var(--err)}
.pill{font-size:11px;background:var(--pri2);color:var(--pri);padding:2px 8px;border-radius:999px;margin-left:6px}
code{background:#14121a;padding:2px 6px;border-radius:6px}code.block{display:block;padding:12px;word-break:break-all}
</style></head><body>
<?php }
function render_footer(): void { echo "</body></html>"; }
