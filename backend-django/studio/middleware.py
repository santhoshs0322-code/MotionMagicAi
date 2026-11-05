class ApiHeaders:
    def __init__(self, get_response):
        self.get_response = get_response
    def __call__(self, request):
        response = self.get_response(request)
        public_catalogue = request.path == "/api/v1/templates" or request.path.startswith("/api/v1/templates/artwork/")
        if request.path.startswith(("/api/", "/auth/")) and not (public_catalogue and response.status_code in (200, 304)):
            response["Cache-Control"] = "no-store"
        if request.path.startswith("/owner/"):
            response["Cache-Control"] = "no-store, private"
            response["X-Robots-Tag"] = "noindex, nofollow"
            response["Referrer-Policy"] = "same-origin"
            response["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' https://*.amazonaws.com; media-src 'self' https://*.amazonaws.com; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response
