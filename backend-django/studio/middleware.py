class ApiHeaders:
    def __init__(self, get_response):
        self.get_response = get_response
    def __call__(self, request):
        from . import web_access
        blocked = web_access.gate(request)
        response = blocked if blocked is not None else self.get_response(request)
        web_access.track(request, response)
        if blocked is not None:
            response["Cache-Control"] = "no-store, private"
        public_catalogue = request.path == "/api/v1/templates" or request.path.startswith("/api/v1/templates/artwork/")
        if request.path.startswith(("/api/", "/auth/")) and not (public_catalogue and response.status_code in (200, 304)):
            response["Cache-Control"] = "no-store"
        if request.path == "/" or request.path.startswith("/app/"):
            if not request.path.startswith(("/app/assets/", "/app/fonts/")):
                response["Cache-Control"] = "no-store, private"
            response["Referrer-Policy"] = "same-origin"
            response["Cross-Origin-Opener-Policy"] = "same-origin-allow-popups"
            response["Content-Security-Policy"] = "default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; font-src 'self'; img-src 'self' data: blob: https://*.amazonaws.com https://lh3.googleusercontent.com https://*.googleusercontent.com; media-src 'self' blob: https://*.amazonaws.com; connect-src 'self' https://accounts.google.com https://*.amazonaws.com; frame-src https://accounts.google.com; form-action 'self' https://test.payu.in https://secure.payu.in; frame-ancestors 'none'; base-uri 'none'; object-src 'none'"
        if request.path.startswith("/owner/"):
            response["Cache-Control"] = "no-store, private"
            response["X-Robots-Tag"] = "noindex, nofollow"
            response["Referrer-Policy"] = "same-origin"
            response["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' https://*.amazonaws.com; media-src 'self' https://*.amazonaws.com; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response
