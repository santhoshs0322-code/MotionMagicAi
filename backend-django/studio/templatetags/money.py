from django import template
from studio.finance import rupees

register = template.Library()
register.filter("money", rupees)
register.filter("credits", lambda value: f"{int(value or 0):,} credits")
