<?php
declare(strict_types=1);

const APP_ROUTES = ['/owner', '/owner/desk', '/caller'];
const JSON_ROUTES = ['/api/public/site', '/api/public/shops'];
const NATIVE_ROUTES = ['/api/voice/sarvam/turn', '/api/voice/sarvam/owner'];
const OWNER_LINK_ROUTE = '/api/twilio/owner-link';
const WEBHOOK_ROUTES = [...NATIVE_ROUTES, OWNER_LINK_ROUTE];
const WEBHOOK_BODY_LIMIT = 32768;

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$path = parse_url($_SERVER['REQUEST_URI'] ?? '', PHP_URL_PATH);
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
if (!in_array($path, [...APP_ROUTES, ...JSON_ROUTES, ...WEBHOOK_ROUTES], true)) {
    http_response_code(404);
    exit;
}
$allowed = in_array($path, WEBHOOK_ROUTES, true) ? ['POST'] : ['GET', 'HEAD'];
if (!in_array($method, $allowed, true)) {
    header('Allow: ' . implode(', ', $allowed));
    http_response_code(405);
    exit;
}

function configuredOrigin(): ?string
{
    $path = dirname(__DIR__) . '/rush-promise-gateway-origin';
    $value = $_SERVER['RUSH_GATEWAY_ORIGIN'] ?? getenv('RUSH_GATEWAY_ORIGIN');
    if (($value === false || $value === '') && is_readable($path)) {
        $value = file_get_contents($path, false, null, 0, 256);
    }
    $origin = is_string($value) ? trim($value) : '';
    return preg_match('#\Ahttps://(?:[a-z0-9]+(?:-[a-z0-9]+)*-[0-9]{2,5}\.app\.github\.dev|[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com)\z#D', $origin)
        ? $origin : null;
}

function respond(int $status, array $data, string $method): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    if ($method !== 'HEAD') echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function unavailable(string $path, string $method): void
{
    if (in_array($path, [...JSON_ROUTES, ...WEBHOOK_ROUTES], true)) {
        respond(503, ['error' => 'The live app is temporarily unavailable.'], $method);
    }
    http_response_code(503);
    header('Content-Type: text/html; charset=utf-8');
    if ($method === 'GET') {
        echo '<!doctype html><html lang="en"><meta charset="utf-8"><title>Rush Promise unavailable</title>'
            . '<main><h1>The live app is temporarily unavailable</h1><p><a href="/">Return to Rush Promise</a></p></main></html>';
    }
    exit;
}

function upstreamRequest(string $target, array $options, int $timeout): array
{
    $body = '';
    $client = curl_init($target);
    if ($client === false) return [0, ''];
    curl_setopt_array($client, $options + [
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT_MS => 1800,
        CURLOPT_TIMEOUT_MS => $timeout,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_WRITEFUNCTION => static function ($handle, string $chunk) use (&$body): int {
            if (strlen($body) + strlen($chunk) > 65536) return 0;
            $body .= $chunk;
            return strlen($chunk);
        }
    ]);
    $ok = curl_exec($client);
    return [$ok ? curl_getinfo($client, CURLINFO_RESPONSE_CODE) : 0, $body];
}

function webhookBody(string $expectedMediaType): string
{
    if (($_SERVER['QUERY_STRING'] ?? '') !== '') {
        respond(400, ['error' => 'Query parameters are unsupported.'], 'POST');
    }
    $mediaType = strtolower(trim(explode(';', $_SERVER['CONTENT_TYPE'] ?? '', 2)[0]));
    if ($mediaType !== $expectedMediaType) {
        respond(415, ['error' => 'Unsupported request content type.'], 'POST');
    }
    if ((int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > WEBHOOK_BODY_LIMIT) {
        respond(413, ['error' => 'Request too large.'], 'POST');
    }
    $request = file_get_contents('php://input', false, null, 0, WEBHOOK_BODY_LIMIT + 1);
    if ($request === false || $request === '') respond(400, ['error' => 'Empty request.'], 'POST');
    if (strlen($request) > WEBHOOK_BODY_LIMIT) respond(413, ['error' => 'Request too large.'], 'POST');
    return $request;
}

function relayNativeTool(string $origin, string $path): void
{
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($auth === '') {
        $auth = $_SERVER['RUSH_VOICE_AUTH'] ?? $_SERVER['REDIRECT_RUSH_VOICE_AUTH']
            ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    }
    if (!preg_match('#\ABearer [a-zA-Z0-9._~+/=-]{1,512}\z#D', $auth)) {
        respond(401, ['error' => 'Authorization required.'], 'POST');
    }
    [$status, $body] = upstreamRequest($origin . $path, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => webhookBody('application/json'),
        CURLOPT_HTTPHEADER => ['Accept: application/json', 'Content-Type: application/json', 'Authorization: ' . $auth]
    ], 12000);
    if ($status < 200 || $status >= 500 || ($status >= 300 && $status < 400)
        || ($body !== '' && !is_array(json_decode($body, true)))) {
        unavailable($path, 'POST');
    }
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo $body;
    exit;
}

function relayOwnerLink(string $origin): void
{
    $signature = $_SERVER['HTTP_X_TWILIO_SIGNATURE'] ?? '';
    if (!preg_match('#\A[a-zA-Z0-9+/]{1,128}={0,2}\z#D', $signature)) {
        respond(401, ['error' => 'Signature required.'], 'POST');
    }
    [$status, $body] = upstreamRequest($origin . OWNER_LINK_ROUTE, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => webhookBody('application/x-www-form-urlencoded'),
        CURLOPT_HTTPHEADER => ['Accept: application/xml', 'Content-Type: application/x-www-form-urlencoded', 'X-Twilio-Signature: ' . $signature]
    ], 12000);
    if ($status >= 400 && $status < 500) respond($status, ['error' => 'Webhook rejected.'], 'POST');
    if ($status !== 200 || !preg_match('#\A(?:<\?xml[^?]*\?>\s*)?<Response(?:\s|/?>)#', trim($body))) {
        unavailable(OWNER_LINK_ROUTE, 'POST');
    }
    header('Content-Type: application/xml; charset=utf-8');
    echo $body;
    exit;
}

$origin = configuredOrigin();
if ($origin === null) unavailable($path, $method);
if (in_array($path, WEBHOOK_ROUTES, true)) {
    if (!function_exists('curl_init')) unavailable($path, $method);
    if ($path === OWNER_LINK_ROUTE) relayOwnerLink($origin);
    relayNativeTool($origin, $path);
}

if (in_array($path, APP_ROUTES, true)) {
    // Browsers preserve the original fragment when the redirect has no fragment.
    $query = $_SERVER['QUERY_STRING'] ?? '';
    if (strlen($query) > 512 || preg_match('/[\x00-\x20\x7f#]/D', $query)) {
        http_response_code(400);
        exit;
    }
    header('Location: ' . $origin . $path . ($query === '' ? '' : '?' . $query), true, 302);
    exit;
}

$target = $origin . $path;
if ($path === '/api/public/shops') {
    $query = $_GET['query'] ?? '';
    if (!is_string($query) || strlen($query) > 320) {
        respond(400, ['error' => 'Use a shorter shop search.'], $method);
    }
    if ($query !== '') $target .= '?query=' . rawurlencode($query);
}

if (!function_exists('curl_init')) unavailable($path, $method);
[$status, $body] = upstreamRequest($target, [
    CURLOPT_HTTPGET => true,
    CURLOPT_HTTPHEADER => ['Accept: application/json']
], 4200);
$data = json_decode($body, true);
if ($status !== 200 || !is_array($data)) unavailable($path, $method);

if ($path === '/api/public/site') {
    $phone = $data['phone'] ?? null;
    $voiceAvailable = ($data['voiceAvailable'] ?? true) === true;
    respond(200, ['phone' => $voiceAvailable && is_string($phone) && preg_match('/\A\+91[0-9]{10}\z/D', $phone) ? $phone : null,
        'voiceAvailable' => $voiceAvailable], $method);
}

$shops = $data['shops'] ?? null;
if (!is_array($shops)) unavailable($path, $method);
$public = [];
foreach (array_slice($shops, 0, 50) as $shop) {
    if (!is_array($shop)) continue;
    $slug = $shop['slug'] ?? null;
    $name = $shop['displayName'] ?? null;
    $area = $shop['area'] ?? null;
    if (!is_string($slug) || strlen($slug) > 55 || !preg_match('/\A[a-z0-9]+(?:-[a-z0-9]+)*\z/D', $slug)
        || !is_string($name) || trim($name) === '' || strlen($name) > 400) continue;
    $public[] = ['slug' => $slug, 'displayName' => $name, 'area' => is_string($area) && strlen($area) <= 400 ? $area : null];
}
respond(200, ['shops' => $public, 'truncated' => ($data['truncated'] ?? false) === true || count($shops) > 50], $method);
