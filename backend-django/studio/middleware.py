class ApiHeaders:
    def __init__(self, get_response):
        self.get_response = get_response
    def __call__(self, request):
        response = self.get_response(request)
        if request.path.startswith(("/api/", "/auth/")):
            response["Cache-Control"] = "no-store"
        if request.path.startswith("/owner/"):
            response["Cache-Control"] = "no-store, private"
            response["X-Robots-Tag"] = "noindex, nofollow"
            response["Referrer-Policy"] = "same-origin"
            response["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response
