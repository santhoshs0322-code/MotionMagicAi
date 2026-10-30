from django import template
from studio.finance import rupees

register = template.Library()
register.filter("money", rupees)
