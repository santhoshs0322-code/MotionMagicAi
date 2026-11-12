from django.conf import settings
from django.core.management.base import BaseCommand
from botocore.exceptions import ClientError
from studio import storage
from studio.web_payments import origin


class Command(BaseCommand):
    help = "Add this web origin to S3 CORS, preserving all other bucket rules."

    def handle(self, *args, **kwargs):
        client = storage.client()
        try:
            rules = client.get_bucket_cors(Bucket=settings.S3_BUCKET).get("CORSRules", [])
        except ClientError as error:
            if error.response.get("Error", {}).get("Code") != "NoSuchCORSConfiguration":
                raise
            rules = []
        rules = [row for row in rules if row.get("ID") != "MotionMagicWeb"]
        rules.append(dict(ID="MotionMagicWeb", AllowedOrigins=[origin()], AllowedMethods=["POST", "GET", "HEAD"],
            AllowedHeaders=["content-type", "range", "x-amz-*"], ExposeHeaders=["ETag", "Content-Length", "Content-Type", "Content-Range", "Accept-Ranges"], MaxAgeSeconds=3600))
        client.put_bucket_cors(Bucket=settings.S3_BUCKET, CORSConfiguration={"CORSRules": rules})
        self.stdout.write("S3 CORS configured for " + origin())
