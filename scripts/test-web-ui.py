"""Offline browser integration check. Start a local TESTING Django server on port 8877.

All identities, quotes, job submission, S3 uploads and PayU orders are fixtures;
no payment or AI provider request is made. Requires playwright and Chrome/Chromium.
"""
import asyncio,json,re,os,shutil
from pathlib import Path
from playwright.async_api import async_playwright

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'artifacts/web-review';OUT.mkdir(parents=True,exist_ok=True);ORIGIN='http://127.0.0.1:8877'
USER={'id':'browser-user-a','email':'creator@example.test','name':'Anjali Sharma','picture':''}
OTHER={'id':'browser-user-b','email':'other@example.test','name':'Other Creator','picture':''}
RATES=[]
for size,price in [('720p',250),('1080p',450)]:
 for audio in [False,True]:
  RATES.append(dict(id=f'pv-{size}-{audio}',title='PixVerse V6',kind='video',max_units=15,
   config=dict(app_model_id='pixverse-v6',resolution=size,aspect_ratios=['9:16','16:9','1:1'],durations=[5,10,15],generated_audio=audio,input_modes=['text','image_and_text']),
   credit_prices={str(n):price*n//10+(40 if audio else 0) for n in [5,10,15]}))
RATES += [dict(id='qwen',title='Qwen Image',kind='image',max_units=1,config=dict(app_model_id='qwen-image',image_size='720p',aspect_ratios=['9:16','1:1','16:9'],input_modes=['text','image_and_text']),credit_prices={'1':20}),
 dict(id='story',title='Kling Story Reference',kind='video',max_units=15,config=dict(app_model_id='kling-o3-reference',resolution='1080p',reference_video=True,max_reference_images=4,workflow='new_scene',generated_audio=False,input_modes=['video_and_text'],durations=[5,10,15],aspect_ratios=['9:16','16:9']),credit_prices={'5':300,'10':600,'15':900}),
 dict(id='long-story',title='Long Story',kind='video',max_units=300,config=dict(app_model_id='long-story',workflow='long_story',resolution='1080p',reference_video=True,optional_reference_video=True,max_reference_images=2,generated_audio=True,input_modes=['text','video_and_text'],durations=list(range(60,301,30)),aspect_ratios=['9:16','16:9','1:1']),credit_prices={str(n):n*50 for n in range(60,301,30)})]
rows=json.loads((ROOT/'backend-django/template-assets/image_templates/catalog.json').read_text(encoding='utf-8'))[:14]
TEMPLATES=[dict(id=r['id'],title=r['title'],category=r['category'],prompt=r['prompt'],mode=r['mode'],requires_reference=True,model_id='qwen-image',resolution='720p',generated_audio=False,aspect_ratio='9:16',poster_url='/api/v1/templates/artwork/'+r['file'],video_url=None) for r in rows]
PACKS=[dict(id=f'web-{c}',title=t+' Pack',credits=c,amount_paise=p,currency='INR') for c,t,p in [(2500,'Creator',149900),(5000,'Pro',299900),(10000,'Studio',599900),(20000,'Ultimate',1199900)]]
GOOGLE="""window.google={accounts:{id:{initialize(o){window.testGoogleOptions=o},renderButton(target){let b=document.createElement('button');b.textContent='Continue with Google';b.onclick=()=>window.testGoogleOptions.callback({credential:'offline-google-test-token'});target.append(b)},disableAutoSelect(){}}}};"""

async def run():
 async with async_playwright() as p:
  chrome=os.environ.get('MOTION_AI_WEB_TEST_BROWSER') or shutil.which('google-chrome') or (r'C:\Program Files\Google\Chrome\Application\chrome.exe' if Path(r'C:\Program Files\Google\Chrome\Application\chrome.exe').is_file() else None)
  browser=await p.chromium.launch(executable_path=chrome,headless=True,args=['--disable-background-networking'])
  context=await browser.new_context(viewport={'width':1440,'height':1000},device_scale_factor=1)
  current={'user':USER,'created':False,'timeout':True,'hold':False,'job':'queued'};submissions=[];quotes=[];orders=[];errors=[]
  page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
  async def reply(route,data,status=200):await route.fulfill(status=status,content_type='application/json',body=json.dumps(data))
  async def intercept(route):
   request=route.request;path=request.url.removeprefix(ORIGIN).split('?')[0]
   if 'accounts.google.com/gsi/client' in request.url:return await route.fulfill(content_type='text/javascript',body=GOOGLE)
   if '.amazonaws.com' in request.url:
    if request.method=='OPTIONS':return await route.fulfill(status=204,headers={'Access-Control-Allow-Origin':ORIGIN,'Access-Control-Allow-Methods':'POST,GET,HEAD','Access-Control-Allow-Headers':'content-type'})
    if current['hold']:await asyncio.sleep(2)
    try:return await route.fulfill(status=204,headers={'Access-Control-Allow-Origin':ORIGIN})
    except Exception:return
   if not request.url.startswith(ORIGIN):return await route.abort()
   if path.startswith('/api/v1/templates/artwork/'):return await route.continue_()
   if path=='/app/session/':return await reply(route,dict(ok=True,user=current['user'],csrf='offline-csrf',google_client_id='google-client',nonce='offline-nonce'))
   if path=='/app/login/':
    assert request.post_data_json['credential']=='offline-google-test-token';current['user']=USER
    return await reply(route,dict(ok=True,user=USER,csrf='after-login-csrf'))
   if path=='/app/logout/':current['user']=OTHER;return await reply(route,dict(ok=True))
   if path=='/api/v1/wallet':return await reply(route,dict(ok=True,available_credits=10000 if current['user']==USER else 420))
   if path=='/api/v1/capabilities':return await reply(route,dict(ok=True,cloud=RATES))
   if path=='/api/v1/templates':return await reply(route,dict(ok=True,templates=TEMPLATES,banners=[]))
   if path=='/api/v1/quotes':
    body=request.post_data_json;quotes.append(body)
    return await reply(route,dict(ok=True,id='quote-owned-123',title='PixVerse V6',maximum_credits=450,available_credits=10000))
   if path=='/api/v1/assets' and request.method=='POST':
    return await reply(route,dict(ok=True,asset={'id':'asset-owned-123'},upload={'url':'https://web-review.s3.amazonaws.com/','fields':{'key':'accounts/browser-user-a/image/uploaded/fixture','Content-Type':'image/jpeg'}}))
   if path=='/api/v1/assets/asset-owned-123':return await reply(route,dict(ok=True,asset={'id':'asset-owned-123','ready':True}))
   if path=='/api/v1/jobs' and request.method=='POST':
    submissions.append((request.headers['idempotency-key'],request.post_data_json));current['created']=True
    if current['timeout']:current['timeout']=False;return await route.abort('connectionfailed')
    return await reply(route,dict(ok=True,id='new-owned-job',title='Cinematic portrait',kind='video',state='queued',created_at='2026-11-12T12:00:00Z'))
   if path=='/api/v1/jobs':
    jobs=[]
    if current['user']==OTHER:jobs=[dict(id='other-job',title='Other account creation',kind='image',state='succeeded',output_asset_id='other-output',created_at='2026-11-12T13:00:00Z')]
    elif current['created']:jobs=[dict(id='new-owned-job',title='Cinematic portrait',kind='video',state=current['job'],created_at='2026-11-12T12:00:00Z',output_asset_id='my-output' if current['job']=='succeeded' else None)]
    return await reply(route,dict(ok=True,results=jobs,next=None,tracked_supported=True))
   if path=='/app/packs/':return await reply(route,dict(ok=True,enabled=True,test=True,packs=PACKS))
   if path=='/app/payments/' and request.method=='GET':return await reply(route,dict(ok=True,orders=[]))
   if path=='/app/payments/' and request.method=='POST':
    orders.append(request.post_data_json)
    return await reply(route,dict(ok=True,order={'id':'payment-id','state':'pending','checkout_url':'/app/payments/11111111-1111-1111-1111-111111111111/checkout/'}))
   if path.endswith('/checkout/'):
    return await route.fulfill(content_type='text/html',body='<html><h1>MotionMagic AI ? PayU checkout</h1><form action="https://test.payu.in/_payment" method="post"><button>Continue to PayU</button></form></html>')
   return await route.continue_()
  await context.route('**/*',intercept)
  await page.goto(ORIGIN+'/app/');await page.wait_for_selector('#studio:not(.hidden)');await page.wait_for_selector('#home-templates img');await page.wait_for_timeout(500)
  assert await page.locator('.creation-card img').count()==4
  await page.locator('.creation-card').first.scroll_into_view_if_needed()
  await page.wait_for_function('() => [...document.querySelectorAll(".creation-card img")].every(image => image.complete && image.naturalWidth > 0)')
  assert await page.locator('#balance .credit-bolt use').get_attribute('href')=='#icon-bolt'
  await page.evaluate('() => scrollTo(0,0)')
  await page.screenshot(path=str(OUT/'web-home-desktop-dark.png'),full_page=True)
  for mode,title in [('image','Create Image'),('text','Text to Video'),('story','Continue Story')]:
   await page.click(f'.creation-card[data-open-mode="{mode}"]')
   assert await page.locator('#create-title').inner_text()==title
   assert await page.locator('#create-cover').get_attribute('src')==await page.locator('#create-cover').get_attribute('data-'+mode)
   await page.click('.sidebar [data-page="home"]')
  await page.click('[data-open-mode="photo"]');await page.set_input_files('#photos',str(ROOT/'backend-django/template-assets/image_templates/02_royal_queen.jpg'))
  await page.fill('#prompt','Cinematic portrait with a slow camera move and warm light.');await page.select_option('#quality','1080p');await page.select_option('#duration','10')
  assert await page.locator('#estimate').inner_text()=='450 credits'
  await page.screenshot(path=str(OUT/'web-create-desktop.png'),full_page=True)
  await page.click('#create');await page.wait_for_selector('#quote-dialog[open]');await page.click('#quote-confirm')
  await page.wait_for_selector('#recover-create:not(.hidden)');assert len(submissions)==1
  await page.click('#recover-create');await page.wait_for_function('() => document.querySelector("#recover-create").classList.contains("hidden")')
  assert len(submissions)==2 and submissions[0]==submissions[1],'Submission recovery must reuse its exact quote/assets/key'
  assert quotes[0]['service']=='pv-1080p-False' and quotes[0]['duration']==10
  # A cancelled upload must immediately allow a fresh request.
  current['hold']=True;await page.click('#create');await page.click('#quote-confirm');await page.wait_for_selector('#generation-progress:not(.hidden)');await page.click('#cancel-upload')
  await page.wait_for_function('() => !document.querySelector("#create").disabled');current['hold']=False;await page.wait_for_timeout(2200)
  assert len(submissions)==2,'Cancelling before queue submission must not create a job'
  await page.click('#create');await page.click('#quote-confirm');await page.wait_for_function('() => !document.querySelector("#create").disabled');assert len(submissions)==3
  current['job']='succeeded';await page.click('.sidebar [data-page="projects"]');await page.wait_for_selector('#projects a');assert '/app/media/my-output/' in await page.locator('#projects a').get_attribute('href')
  await page.screenshot(path=str(OUT/'web-projects-desktop.png'),full_page=True)
  await page.click('.sidebar [data-page="create"]');await page.click('[data-mode="long"]');await page.select_option('#duration','150');assert await page.locator('#estimate').inner_text()=='7500 credits'
  assert await page.locator('#sound').is_checked() and await page.locator('#sound').is_disabled()
  await page.screenshot(path=str(OUT/'web-long-story-desktop.png'),full_page=True)
  await page.click('.sidebar [data-page="credits"]');await page.wait_for_selector('.pack-card');assert '1,499' in await page.locator('.pack-card').first.inner_text()
  await page.screenshot(path=str(OUT/'web-credits-desktop.png'),full_page=True)
  await page.click('.sidebar [data-page="create"]');await page.fill('#prompt','This private draft must not leak to another Google account.')
  await page.click('#signout');await page.wait_for_function('() => document.querySelector("#credit-count").textContent==="420"')
  await page.click('.sidebar [data-page="create"]');assert await page.input_value('#prompt')=='';assert await page.locator('#media-previews').inner_text()==''
  # Desktop and narrow mobile layouts use the same flows and preserve light-mode readability.
  await page.set_viewport_size({'width':390,'height':844});await page.click('.mobile-nav [data-page="home"]');await page.click('#theme');await page.wait_for_timeout(200)
  assert await page.evaluate('() => document.documentElement.scrollWidth<=innerWidth'),'Mobile layout must not overflow horizontally'
  await page.screenshot(path=str(OUT/'web-home-mobile-light.png'),full_page=True)
  await page.click('.mobile-nav [data-page="create"]');await page.click('[data-mode="long"]');await page.select_option('#duration','210');assert await page.locator('#estimate').inner_text()=='10500 credits'
  await page.screenshot(path=str(OUT/'web-create-mobile-light.png'),full_page=True)
  await page.click('.mobile-nav [data-page="credits"]');await page.wait_for_selector('.pack-card');await page.fill('#phone','9876543210');await page.locator('.pack-card button').first.click();await page.wait_for_url('**/checkout/')
  assert orders[-1]['pack']=='web-2500' and set(orders[-1])=={'pack','phone','request_key'}
  # A fresh browser gets the real Google-button integration, not an unlocked studio.
  current['user']=None;await page.goto(ORIGIN+'/app/');await page.wait_for_selector('#login:not(.hidden)');await page.wait_for_selector('#google-button button')
  assert await page.locator('#public-header').is_visible()
  assert await page.locator('.site-footer').is_visible()
  assert await page.evaluate('() => document.documentElement.scrollWidth<=innerWidth')
  await page.screenshot(path=str(OUT/'web-login-mobile.png'),full_page=True)
  await page.set_viewport_size({'width':1440,'height':1000})
  await page.screenshot(path=str(OUT/'web-login-desktop.png'),full_page=True)
  await page.click('#google-button button');await page.wait_for_selector('#studio:not(.hidden)')
  assert not await page.locator('#public-header').is_visible()
  assert not errors,errors
  print('PASS: desktop/mobile, themes, server-rate choices, half-minute stories, quote/upload/submit/recovery, cancel/retry, projects, separate PayU prices, account-switch isolation, Google callback.')
  await browser.close()
asyncio.run(run())
