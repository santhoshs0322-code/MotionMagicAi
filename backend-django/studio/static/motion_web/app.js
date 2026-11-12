'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const memoryStore=new Map();
  const savedStore={getItem(key){try{return sessionStorage.getItem(key)||memoryStore.get(key)||null;}catch(_){return memoryStore.get(key)||null;}},setItem(key,value){memoryStore.set(key,value);try{sessionStorage.setItem(key,value);}catch(_){}},removeItem(key){memoryStore.delete(key);try{sessionStorage.removeItem(key);}catch(_){}}};
  const state = {user:null, csrf:document.querySelector('meta[name=csrf-token]').content, page:'home', mode:'photo',
    rates:[], templates:[], photos:[], video:null, videoChoice:0, videoChecking:false, urls:[], jobs:new Map(), filter:'all', next:null,
    quote:null, busy:false, createRun:0, epoch:0, requests:new Set(), submission:null, packs:[], paymentBusy:false};
  const labels = {photo:'Image to Video',text:'Text to Video',image:'Create Image',story:'Continue Story',long:'Long Story'};
  let pollTimer, toastTimer, uploadRequest, packsRun=0, feedbackContext=null, feedbackLoading=false, feedbackSaving=false;
  state.testing={enabled:false,welcome_credits:100};
  const activeStates = new Set(['queued','submitting','running','uncertain']);
  const text = (tag,value,cls) => {const node=document.createElement(tag);node.textContent=value;if(cls)node.className=cls;return node;};
  const button = (value,action,cls='quiet') => {const node=text('button',value,cls);node.type='button';node.onclick=action;return node;};
  const money = amount => new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(amount/100);
  function toast(message){$('toast').textContent=message;$('toast').classList.remove('hidden');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.add('hidden'),6500);}
  async function api(path,options={}) {
    const address=new URL(path,location.origin);
    if(address.origin!==location.origin)throw Error('Invalid application address.');
    const controller=new AbortController(), epoch=state.epoch;
    state.requests.add(controller);
    const timer=setTimeout(()=>controller.abort(),25000);
    try {
      const response=await fetch(address,{...options,credentials:'same-origin',signal:controller.signal,
        headers:{'Accept':'application/json',...(options.body?{'Content-Type':'application/json'}:{}),'X-CSRFToken':state.csrf,...(state.user?{'X-Motion-Account':state.user.id}:{}),...options.headers}});
      const data=await response.json().catch(()=>({message:'The server could not complete this request.'}));
      if(epoch!==state.epoch)throw new DOMException('Account changed','AbortError');
      if(!response.ok||data.ok===false){if(response.status===401&&state.user){forgetAccount();queueMicrotask(initialize);throw new DOMException('Session changed','AbortError');}const error=Error(data.message||'Please try again.');error.status=response.status;throw error;}
      return data;
    } finally {clearTimeout(timer);state.requests.delete(controller);}
  }
  function forgetAccount(){
    feedbackContext=null;feedbackLoading=false;feedbackSaving=false;$('feedback-form').reset();clearFeedbackMedia();
    delete document.body.dataset.authenticated;
    if(state.user)savedStore.removeItem('motion-web-purchase:'+state.user.id);clearTimeout(toastTimer);$('toast').textContent='';$('toast').classList.add('hidden');
    state.mode='photo';state.filter='all';state.templateReferenceRequired=false;for(const select of ['model','quality','ratio','duration'])$(select).replaceChildren();$('sound').checked=false;$('credit-count').textContent='?';
    state.epoch++;state.createRun++;for(const request of state.requests)request.abort();state.requests.clear();
    uploadRequest?.abort();uploadRequest=null;clearTimeout(pollTimer);
    for(const url of state.urls)URL.revokeObjectURL(url);state.urls=[];
    state.user=null;state.photos=[];state.video=null;state.videoChoice++;state.videoChecking=false;state.jobs.clear();state.submission=null;state.quote=null;
    setBusy(false);state.paymentBusy=false;state.packs=[];state.next=null;
    $('prompt').value='';$('photos').value='';$('video').value='';$('phone').value='';
    $('projects').replaceChildren();$('orders').replaceChildren();$('media-previews').replaceChildren();
    $('preview-media').replaceChildren();$('create-error').textContent='';$('payment-error').textContent='';
    $('recover-create').classList.add('hidden');$('generation-progress').classList.add('hidden');
    for(const dialog of document.querySelectorAll('dialog'))if(dialog.open)dialog.close();
    $('studio').classList.add('hidden');
  }
  async function initialize(){
    try {
      const config=await api('/app/session/');state.csrf=config.csrf;applyTesting(config.testing);
      $('startup').classList.add('hidden');
      if(config.visitor_prompt&&state.testing.enabled)window.MotionWelcome?.open(config.csrf,()=>loadFeedback());
      if(config.user){state.user=config.user;await enterStudio();return;}
      $('login').classList.remove('hidden');
      if(!config.google_client_id){$('login-error').textContent='Google sign-in is not enabled on this server yet.';return;}
      const started=Date.now();
      const show=()=>{
        if(!window.google?.accounts?.id){if(Date.now()-started<12000)setTimeout(show,200);else $('login-error').textContent='Google sign-in could not load. Check your connection and reload.';return;}
        google.accounts.id.initialize({client_id:config.google_client_id,nonce:config.nonce,ux_mode:'popup',auto_select:false,
          callback:async result=>{
            $('login-error').textContent='';
            try {const data=await api('/app/login/',{method:'POST',body:JSON.stringify({credential:result.credential})});
              state.user=data.user;state.csrf=data.csrf;if(data.testing)applyTesting(data.testing);await enterStudio();}
            catch(error){$('login-error').textContent=error.message;}
          }});
        $('google-button').replaceChildren();
        google.accounts.id.renderButton($('google-button'),{theme:'outline',size:'large',shape:'pill',width:Math.min(360,$('google-button').clientWidth||320),text:'continue_with'});
      };show();
    } catch(error){$('startup').classList.add('hidden');$('login').classList.remove('hidden');$('login-error').textContent=error.message;}
  }
  async function enterStudio(){
    document.body.dataset.authenticated="true";
    $('login').classList.add('hidden');$('studio').classList.remove('hidden');
    $('account-name').textContent=state.user.name;try{localStorage.setItem('motion-web-account',state.user.id);}catch(_){}showPage(new URLSearchParams(location.search).get('page')==='credits'?'credits':'home');
    try{state.submission=JSON.parse(savedStore.getItem('motion-submit:'+state.user.id)||'null');}catch(_){state.submission=null;}
    $('recover-create').classList.toggle('hidden',!state.submission);
    const results=await Promise.allSettled([refreshWallet(),loadCatalog(),loadTemplates()]);
    if(results.some(result=>result.status==='rejected'))toast('Some studio information could not load. Reconnect and retry.');
    await Promise.allSettled([refreshProjects(false,true),api('/api/v1/jobs?scope=active').then(data=>{for(const job of data.results)state.jobs.set(job.id,job);renderProjects();schedulePoll();})]);
    const payment=new URLSearchParams(location.search).get('payment');
    if(payment){history.replaceState(null,'','/app/');showPage('credits');await checkOrder(payment);}
    if(state.testing.enabled)await loadFeedback();
  }
  async function refreshWallet(){const data=await api('/api/v1/wallet');$('credit-count').textContent=new Intl.NumberFormat().format(data.available_credits);state.available=data.available_credits;state.balance=data.balance_credits??data.available_credits;}
  function applyTesting(config){
    state.testing=config||{enabled:false,welcome_credits:100};
    $('trial-credit-info').classList.toggle('hidden',!state.testing.enabled);
    $('paid-credit-controls').classList.toggle('hidden',state.testing.enabled);
    $('trial-credit-copy').textContent=`New web accounts receive ${state.testing.welcome_credits} one-time welcome credits. Web testing is limited to ${state.testing.welcome_credits} credits per account. Existing shared credits stay available on Android; signing in again does not refill the testing allowance.`;
    document.querySelector('#page-credits .credits-hero h1').textContent=state.testing.enabled?'Thanks for creating with us.':'More credits. More possibilities.';
    for(const node of document.querySelectorAll('.sidebar [data-page="credits"] span,.mobile-nav [data-page="credits"] span'))node.textContent=state.testing.enabled?'Pricing & feedback':'Add Credits';
  }
  function showFeedback(context,redirect=false){
    if(!state.testing.enabled||!state.user||feedbackContext||document.querySelector('dialog[open]'))return;
    feedbackContext={...context,redirect,epoch:state.epoch};$('feedback-form').reset();clearFeedbackMedia();$('feedback-error').textContent='';
    const generation=context.context==='generation';
    $('feedback-title').textContent=generation?'How was your creation?':state.available>0?'How is your testing experience?':'You have used your testing credits';
    $('feedback-description').textContent=generation?(context.job?.title||'Your result is ready. Tell us what you think.'):'Purchases are temporarily paused. Would you like to keep creating when we launch?';
    $('feedback-rating').classList.toggle('hidden',!generation);
    for(const input of $('feedback-rating').querySelectorAll('input'))input.required=generation;
    const image=$('feedback-thumbnail');image.classList.toggle('hidden',!context.job?.thumbnail_url);
    if(context.job?.thumbnail_url)image.src=context.job.thumbnail_url;else image.removeAttribute('src');
    $('feedback-preview').classList.toggle('hidden',!context.job?.output_asset_id);
    $('feedback-dialog').showModal();
  }
  function clearFeedbackMedia(){const video=$('feedback-video');video.pause();video.removeAttribute('src');video.load();video.classList.add('hidden');$('feedback-preview').disabled=false;}
  async function previewFeedback(){
    const context=feedbackContext;if(!context?.job?.output_asset_id)return;$('feedback-preview').disabled=true;
    try{const data=await api('/api/v1/assets/'+context.job.output_asset_id);if(feedbackContext!==context)return;
      if(context.job.kind==='video'){$('feedback-video').src=data.url;$('feedback-video').classList.remove('hidden');$('feedback-thumbnail').classList.add('hidden');}
      else{$('feedback-thumbnail').src=data.url;$('feedback-thumbnail').classList.remove('hidden');}
      $('feedback-preview').classList.add('hidden');
    }catch(error){if(feedbackContext===context)$('feedback-error').textContent=error.message;}
    finally{if(feedbackContext===context)$('feedback-preview').disabled=false;}
  }
  async function loadFeedback(){
    if(!state.testing.enabled||!state.user||state.busy||feedbackLoading||feedbackContext||document.querySelector('dialog[open]'))return;
    const epoch=state.epoch;feedbackLoading=true;
    try{const data=await api('/app/feedback/');if(epoch!==state.epoch)return;
      if(data.pending.length){const job=data.pending[0];showFeedback({context:'generation',job_id:job.id,job},data.exhausted&&data.pending.length===1);}
      else if(data.exhausted&&!data.credits_answered)showFeedback({context:'credits'},true);
    }catch(_){}finally{if(epoch===state.epoch)feedbackLoading=false;}
  }
  async function saveFeedback(outcome){
    const context=feedbackContext;if(!context||feedbackSaving)return;
    if(outcome==='submitted'&&!$('feedback-form').reportValidity())return;
    feedbackSaving=true;$('feedback-submit').disabled=true;$('feedback-skip').disabled=true;$('feedback-error').textContent='';
    const form=new FormData($('feedback-form'));
    const payload={context:context.context,outcome};if(context.job_id)payload.job_id=context.job_id;
    if(outcome==='submitted'){payload.rating=context.context==='generation'?(form.get('rating')||''):'';payload.interest=form.get('interest')||'';payload.comment=form.get('comment')||'';}
    try{await api('/app/feedback/',{method:'POST',body:JSON.stringify(payload)});if(context.epoch!==state.epoch)return;
      feedbackContext=null;clearFeedbackMedia();$('feedback-dialog').close();if(outcome==='submitted')toast('Thank you. Your feedback helps us improve.');
      if(context.redirect){location.assign('/app/pricing/');return;}await loadFeedback();
    }catch(error){if(context.epoch===state.epoch){if(outcome==='dismissed'){feedbackContext=null;clearFeedbackMedia();$('feedback-dialog').close();if(context.redirect)location.assign('/app/pricing/');}else $('feedback-error').textContent=error.message;}}
    finally{if(context.epoch===state.epoch){feedbackSaving=false;$('feedback-submit').disabled=false;$('feedback-skip').disabled=false;}}
  }
  async function loadCatalog(){const data=await api('/api/v1/capabilities');state.rates=data.cloud||[];renderModels();}
  async function loadTemplates(){const data=await api('/api/v1/templates');state.templates=data.templates||[];renderTemplates();}
  function showPage(page){
    const changed=state.page!==page;
    state.page=page;
    for(const name of ['home','create','templates','projects','credits'])$('page-'+name).classList.toggle('hidden',name!==page);
    for(const node of document.querySelectorAll('[data-page]'))node.classList.toggle('active',node.dataset.page===page);
    $('page-title').textContent={home:'Make something magical.',create:labels[state.mode],templates:'Your next inspiration.',projects:'Made by you.',credits:'Fuel your creativity.'}[page];
    if(page==='projects')refreshProjects(false).catch(error=>$('projects-error').textContent=error.message);
    if(page==='credits'&&!state.testing.enabled)loadPacks().catch(error=>$('payment-error').textContent=error.message);
    if(page==='create')renderModels();
    if(changed)window.scrollTo({top:0,behavior:'instant'});
  }
  function visibleRates(){return state.rates.filter(rate=>{
    const c=rate.config||{};
    if(state.mode==='image')return rate.kind==='image';
    if(rate.kind!=='video')return false;
    if(state.mode==='long')return c.workflow==='long_story';
    if(c.workflow==='long_story')return false;
    if(state.mode==='story')return !!c.reference_video;
    if(c.reference_video)return false;
    return (c.input_modes||[]).includes(state.mode==='photo'?'image_and_text':'text');
  });}
  function options(node,values,current){node.replaceChildren();for(const [value,label] of values){const option=text('option',label);option.value=value;node.append(option);}if(values.some(x=>String(x[0])===String(current)))node.value=current;}
  const modelId=rate=>rate.config.app_model_id||rate.title;
  function selectedRate(){
    const candidates=visibleRates().filter(r=>modelId(r)===$('model').value && (r.config.resolution||r.config.image_size||'Default')===$('quality').value);
    return candidates.find(r=>r.config.generated_audio===$('sound').checked)||candidates[0]||null;
  }
  function renderModels(){
    const rows=visibleRates(), unique=new Map(rows.map(r=>[modelId(r),r.title.replace(/\s*[·|].*$/,'')]));
    options($('model'),[...unique.entries()],$('model').value);renderQualities();
  }
  function renderQualities(){
    const sizes=[...new Set(visibleRates().filter(r=>modelId(r)===$('model').value).map(r=>r.config.resolution||r.config.image_size||'Default'))];
    options($('quality'),sizes.map(value=>[value,value]),$('quality').value);renderSettings();
  }
  function renderSettings(){
    $('page-create').dataset.mode=state.mode;
    $('create-title').textContent=labels[state.mode];
    $('create-description').textContent={photo:'Give your photo a little motion. Make the moment unforgettable.',text:'Set the scene with a few words. Let your imagination take the lead.',image:'From a spark of inspiration to an image only you could imagine.',story:'Keep your characters close. Take their story somewhere new.',long:'One idea, multiple scenes. Build a longer story with speaking characters.'}[state.mode];
    const cover=$('create-cover'),source=cover.dataset[state.mode];
    if(cover.getAttribute('src')!==source)cover.src=source;
    const rate=selectedRate();state.rate=rate;
    options($('ratio'),(rate?.config.aspect_ratios||[]).map(value=>[value,value]),$('ratio').value||'9:16');
    options($('duration'),(rate?.config.durations||[]).map(seconds=>[seconds,state.mode==='long'?`${seconds/60} min`:`${seconds}s`]),$('duration').value);
    $('duration-label').classList.toggle('hidden',state.mode==='image');
    $('sound-label').classList.toggle('hidden',state.mode==='image');
    $('sound').checked=rate?.config.generated_audio===true;
    const variants=visibleRates().filter(r=>modelId(r)===$('model').value && (r.config.resolution||r.config.image_size)===$('quality').value);
    $('sound').disabled=new Set(variants.map(r=>r.config.generated_audio)).size<2||state.mode==='long';
    $('sound-copy').textContent=state.mode==='long'?'Character dialogue included':state.mode==='story'&&rate?.config.workflow==='new_scene'?'Keep reference audio':'Generate audio';
    $('audio-help').textContent=state.mode==='story'&&rate?.config.workflow==='new_scene'?'Silent reference videos remain silent. This option keeps existing reference audio.':state.mode==='long'?'Faces, voices and lip sync can vary between scenes.':'';
    $('references').classList.toggle('hidden',state.mode==='text');
    $('video-zone').classList.toggle('hidden',!['story','long'].includes(state.mode));
    $('photo-label').textContent=state.mode==='long'?'Character photos':'Add a photo';
    $('reference-help').textContent=state.mode==='long'?'Optional: one or two photos, a reference video, both, or your prompt alone. Photos are @Element1 and @Element2; a video takes the next number.':state.mode==='story'?`Add a reference MP4 and up to ${rate?.config.max_reference_images||0} optional photos. Use @Video1 and @Image1 in your prompt.`:state.mode==='image'?'Add a photo if you want to recreate a look or guide your image.':'Upload the photo you want to bring into motion.';
    $('mode-help').textContent=state.mode==='long'?'Include the dialogue language and character voice descriptions.':'Describe what you want to see. Your settings stay in this draft.';
    $('create').disabled=!rate||state.busy||state.videoChecking;estimate();
    for(const node of document.querySelectorAll('[data-mode]'))node.classList.toggle('active',node.dataset.mode===state.mode);
  }
  function estimate(){const rate=selectedRate();state.rate=rate;const units=state.mode==='image'?1:Number($('duration').value);const price=rate?.credit_prices?.[units];$('estimate').textContent=price==null?'Unavailable':`${price} credits`;$('create-price').textContent=price==null?'':`${price} credits`;}
  function setMode(mode){if(state.busy){toast('Finish or cancel your upload first.');return;}state.mode=mode;state.templateReferenceRequired=false;clearMedia();$('prompt').value='';$('create-error').textContent='';renderModels();showPage('create');promptCount();}
  function clearMedia(){state.videoChoice++;state.videoChecking=false;for(const url of state.urls)URL.revokeObjectURL(url);state.urls=[];state.photos=[];state.video=null;$('photos').value='';$('video').value='';$('media-previews').replaceChildren();if(!state.busy)renderSettings();}
  function previews(){for(const url of state.urls)URL.revokeObjectURL(url);state.urls=[];$('media-previews').replaceChildren();
    for(const [index,file] of state.photos.entries()){const box=text('div','','media-preview'),url=URL.createObjectURL(file),image=document.createElement('img');state.urls.push(url);image.src=url;image.alt=`Reference photo ${index+1}`;box.append(image,button('×',()=>{state.photos.splice(index,1);previews();}));$('media-previews').append(box);}
    if(state.video){const box=text('div','','media-preview'),video=document.createElement('video'),url=URL.createObjectURL(state.video);state.urls.push(url);video.src=url;video.muted=true;video.preload='metadata';box.append(video,button('×',()=>{state.video=null;previews();}));$('media-previews').append(box);}
  }
  function promptCount(){$('prompt-count').textContent=`${$('prompt').value.length}/1000`;}
  function setBusy(value){state.busy=value;for(const id of ['model','quality','ratio','duration','sound','photos','video','prompt','clear-media','clear-prompt','inspire'])$(id).disabled=value;for(const node of $('media-previews').querySelectorAll('button'))node.disabled=value;$('create').disabled=value||!state.rate||state.videoChecking;$('generation-progress').classList.toggle('hidden',!value);$('cancel-upload').disabled=false;if(!value){renderSettings();if(state.testing.enabled)queueMicrotask(loadFeedback);}}
  async function normalizedImage(file){
    if(file.size>20*1024*1024)throw Error('Choose a photo smaller than 20 MB.');
    const bitmap=await createImageBitmap(file);try{if(bitmap.width*bitmap.height>32_000_000)throw Error('Choose a photo smaller than 32 megapixels.');
      const factor=Math.min(1,2048/Math.max(bitmap.width,bitmap.height)),canvas=document.createElement('canvas');canvas.width=Math.round(bitmap.width*factor);canvas.height=Math.round(bitmap.height*factor);
      const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(bitmap,0,0,canvas.width,canvas.height);
      return await new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(Error('Could not prepare the photo.')),'image/jpeg',.94));
    } finally {bitmap.close();}
  }
  async function videoInfo(file){
    const media=document.createElement('video'),url=URL.createObjectURL(file);let timer;
    try{media.preload='metadata';media.src=url;return await new Promise((resolve,reject)=>{
      timer=setTimeout(()=>reject(Error('Video metadata could not load. Choose another MP4.')),10000);
      media.onloadedmetadata=()=>resolve({duration:media.duration,width:media.videoWidth,height:media.videoHeight});
      media.onerror=()=>reject(Error('This browser could not read the reference MP4.'));
    });}finally{clearTimeout(timer);media.onloadedmetadata=null;media.onerror=null;URL.revokeObjectURL(url);media.removeAttribute('src');media.load();}
  }
  async function upload(file,kind,epoch,run){
    const blob=kind==='image'?await normalizedImage(file):file,mime=kind==='image'?'image/jpeg':'video/mp4';
    if(epoch!==state.epoch||!state.busy||run!==state.createRun)throw new DOMException('Cancelled','AbortError');
    const data=await api('/api/v1/assets',{method:'POST',body:JSON.stringify({mime,size:blob.size,kind})});
    const target=new URL(data.upload.url);if(target.protocol!=='https:'||!target.hostname.endsWith('.amazonaws.com'))throw Error('Invalid storage upload address.');
    const form=new FormData();for(const [key,value] of Object.entries(data.upload.fields))form.append(key,value);form.append('file',blob,'reference'+(kind==='image'?'.jpg':'.mp4'));
    await new Promise((resolve,reject)=>{const xhr=new XMLHttpRequest();uploadRequest=xhr;xhr.open('POST',target);xhr.timeout=300000;
      xhr.upload.onprogress=event=>{if(event.lengthComputable)$('upload-progress').value=event.loaded/event.total*100;};
      xhr.onload=()=>{uploadRequest=null;xhr.status>=200&&xhr.status<300?resolve():reject(Error('Upload failed. Check your connection and storage CORS configuration.'));};
      xhr.onerror=()=>{uploadRequest=null;reject(Error('Upload could not reach storage. Please retry.'));};
      xhr.ontimeout=()=>{uploadRequest=null;reject(Error('Upload timed out. Please retry.'));};xhr.onabort=()=>{uploadRequest=null;reject(new DOMException('Cancelled','AbortError'));};xhr.send(form);
    });
    if(epoch!==state.epoch||!state.busy||run!==state.createRun)throw new DOMException('Cancelled','AbortError');
    await api('/api/v1/assets/'+data.asset.id,{method:'POST',body:'{}'});return data.asset.id;
  }
  function draftFingerprint(){return JSON.stringify([state.mode,$('model').value,$('quality').value,$('ratio').value,$('duration').value,$('sound').checked,$('prompt').value,state.photos.map(f=>[f.name,f.size,f.lastModified]),state.video?[state.video.name,state.video.size,state.video.lastModified]:null]);}
  async function beginCreate(){
    if(state.busy||state.videoChecking)return;if(state.submission){toast('Recover your pending creation before starting another.');return;}$('create-error').textContent='';const rate=selectedRate();
    try{
      if(!rate)throw Error('Choose an available model.');if(!$('prompt').value.trim())throw Error('Describe your creation first.');
      if((state.mode==='photo'||state.templateReferenceRequired)&&!state.photos.length)throw Error('Add a starting photo.');
      if(state.mode==='story'&&!state.video)throw Error('Add a reference video.');
      if(state.mode==='long'&&state.photos.length>2)throw Error('Long Story uses up to two photos.');
      const references=['story','long'].includes(state.mode)?[...state.photos]:[];
      if(state.mode==='story'&&references.length>(rate.config.max_reference_images||0))throw Error('This model supports fewer reference photos.');
      const main=state.video&&['story','long'].includes(state.mode)?state.video:!['long','story','text'].includes(state.mode)?state.photos[0]:null;
      const input=main===state.video&&main?'video':main?'image':'text';
      const payload={service:rate.id,prompt:$('prompt').value.trim(),input_kind:input,aspect_ratio:$('ratio').value,reference_image_count:references.length};
      if(state.mode!=='image'){payload.duration=Number($('duration').value);payload.generated_audio=rate.config.generated_audio===true;}
      if(rate.config.duration_from_source){
        const video=document.createElement('video'),url=URL.createObjectURL(main);try{video.src=url;await new Promise((resolve,reject)=>{video.onloadedmetadata=resolve;video.onerror=()=>reject(Error('Could not read video duration.'));});payload.duration=Math.ceil(video.duration);if(!Number.isFinite(payload.duration))throw Error('Could not read video duration.');}finally{URL.revokeObjectURL(url);video.removeAttribute('src');video.load();}
      }
      const draftVersion=draftFingerprint();$('create').disabled=true;
      const quote=await api('/api/v1/quotes',{method:'POST',body:JSON.stringify(payload)});
      if(draftVersion!==draftFingerprint())throw Error('Your draft changed. Press Create again for an updated quote.');
      state.quote={...quote,main,references,input,epoch:state.epoch};$('quote-title').textContent=quote.title;$('quote-cost').textContent=`${quote.maximum_credits} credits`;$('quote-balance').textContent=`Available: ${quote.available_credits} credits`;$('quote-error').textContent='';
      if(state.testing.enabled&&quote.available_credits<quote.maximum_credits){showFeedback({context:'credits'},true);return;}
      $('quote-confirm').textContent=quote.available_credits<quote.maximum_credits?'Add credits':'Confirm creation';$('quote-dialog').showModal();
    }catch(error){if(error.status===402&&state.testing.enabled)showFeedback({context:'credits'},true);else if(error.name!=='AbortError')$('create-error').textContent=error.message;}
    finally{$('create').disabled=!state.rate||state.busy;}
  }
  async function confirmCreate(){
    if(state.busy)return;const quote=state.quote;if(!quote||quote.epoch!==state.epoch)return;
    if(quote.available_credits<quote.maximum_credits){$('quote-dialog').close();showPage('credits');return;}
    const run=++state.createRun;$('quote-dialog').close();setBusy(true);$('generation-stage').textContent='Preparing your references…';$('upload-progress').value=0;
    try{
      const body={quote_id:quote.id,reference_image_ids:[]};
      if(quote.main){$('generation-stage').textContent='Uploading your reference…';body.asset_id=await upload(quote.main,quote.input,quote.epoch,run);}
      for(const file of quote.references){$('generation-stage').textContent='Uploading character photos…';body.reference_image_ids.push(await upload(file,'image',quote.epoch,run));}
      if(quote.epoch!==state.epoch||!state.busy||run!==state.createRun)throw new DOMException('Cancelled','AbortError');
      state.submission={body,key:crypto.randomUUID(),account:state.user.id};savedStore.setItem('motion-submit:'+state.user.id,JSON.stringify(state.submission));
      await submitSaved();
    }catch(error){if(error.status===402&&state.testing.enabled)showFeedback({context:'credits'},true);else if(error.name!=='AbortError')$('create-error').textContent=error.message;}
    finally{if(quote.epoch===state.epoch&&run===state.createRun){setBusy(false);$('recover-create').classList.toggle('hidden',!state.submission);}}
  }
  async function submitSaved(){
    if(!state.submission||state.submission.account!==state.user?.id)return;
    $('cancel-upload').disabled=true;$('generation-stage').textContent='Saving your creation to the cloud queue…';
    const saved=state.submission,result=await api('/api/v1/jobs',{method:'POST',headers:{'Idempotency-Key':saved.key},body:JSON.stringify(saved.body)});
    savedStore.removeItem('motion-submit:'+state.user.id);state.submission=null;$('recover-create').classList.add('hidden');
    state.jobs.set(result.id,result);toast('Your creation is running in the cloud. Follow it in My Projects.');await refreshWallet().catch(()=>{});schedulePoll();
  }
  async function refreshProjects(more=false,silent=false){
    if(!state.user)return;if(!silent)$('projects-error').textContent='';
    const result=await api(more&&state.next?state.next:'/api/v1/jobs');
    if(!more){const pending=[...state.jobs.values()].filter(job=>activeStates.has(job.state));state.jobs.clear();for(const job of pending)state.jobs.set(job.id,job);}for(const job of result.results)state.jobs.set(job.id,job);state.next=result.next;renderProjects();schedulePoll();
    if(!silent&&state.testing.enabled)await loadFeedback();
  }
  function schedulePoll(){clearTimeout(pollTimer);if(state.user&&[...state.jobs.values()].some(job=>activeStates.has(job.state)))pollTimer=setTimeout(pollActive,30000);}
  async function pollActive(){
    if(document.hidden){schedulePoll();return;}
    try{const old=[...state.jobs.values()].filter(j=>activeStates.has(j.state)).map(j=>j.id).slice(0,12);if(!old.length)return;
      const result=await api('/api/v1/jobs?scope=active&tracked='+old.join(','));let finished=false;
      for(const job of result.results){const previous=state.jobs.get(job.id);if(previous&&activeStates.has(previous.state)&&!activeStates.has(job.state)){finished=true;if(job.state==='succeeded')toast('Your '+(job.kind==='image'?'image':'video')+' is ready in My Projects.');else if(job.state==='failed')toast('Your creation could not finish. Reserved credits were released.');}state.jobs.set(job.id,job);}renderProjects();if(finished){await refreshWallet();if(state.testing.enabled)await loadFeedback();}
    }catch(error){if(error.status===401){forgetAccount();initialize();}}
    finally{schedulePoll();}
  }
  function renderProjects(){
    $('projects').replaceChildren();const rows=[...state.jobs.values()].sort((a,b)=>String(b.created_at).localeCompare(String(a.created_at)));
    for(const job of rows.filter(job=>state.filter==='all'||job.kind===state.filter)){
      const card=text('article','','project-card'),art=text('div','','project-art');
      if(job.thumbnail_url&&job.state==='succeeded'){const image=document.createElement('img');image.src=job.thumbnail_url;image.alt=job.title;image.loading='lazy';art.append(image);}else art.append(text('span',job.error_code==='content_policy_violation'?'⊘':job.kind==='image'?'✦':'▷'));
      const status=activeStates.has(job.state)?job.story_scenes_total?`Scene ${job.story_scenes_done} / ${job.story_scenes_total}`:'Creating…':job.state==='succeeded'?'Ready':job.state==='failed'?'Failed · credits released':'Cancelled';
      art.append(text('small',status,'project-status'));card.append(art,text('h3',job.title||'Your creation'));
      const actions=text('div','','project-actions');
      if(job.state==='succeeded'&&job.output_asset_id){actions.append(button('Preview',()=>previewResult(job)));const link=text('a','Download','quiet');link.href='/app/media/'+job.output_asset_id+'/';link.download='';actions.append(link);}
      if(job.state==='queued')actions.append(button('Cancel',async()=>{try{const result=await api('/api/v1/jobs/'+job.id,{method:'DELETE'});state.jobs.set(job.id,result);renderProjects();await refreshWallet();}catch(error){toast(error.message);}}));
      if(job.error_code==='content_policy_violation')card.append(text('p','Try a different photo or prompt.'));
      card.append(actions);$('projects').append(card);
    }
    if(!rows.length)$('projects').append(text('p','Your cloud creations will appear here.','muted'));
    $('more-projects').classList.toggle('hidden',!state.next);
  }
  async function previewResult(job){try{const data=await api('/api/v1/assets/'+job.output_asset_id);$('preview-media').replaceChildren();const media=document.createElement(job.kind==='image'?'img':'video');media.src=data.url;if(job.kind!=='image'){media.controls=true;media.preload='metadata';}else media.alt=job.title;$('preview-media').append(media);$('preview-title').textContent=job.title;$('use-template').classList.add('hidden');$('download-result').classList.remove('hidden');$('download-result').href='/app/media/'+job.output_asset_id+'/';$('preview-dialog').showModal();}catch(error){toast(error.message);}}
  function renderTemplates(){const query=$('template-search').value.toLowerCase();for(const [target,entries] of [[$('home-templates'),state.templates.slice(0,10)],[$('templates'),state.templates.filter(t=>(t.title+' '+t.category).toLowerCase().includes(query))]]){target.replaceChildren();for(const item of entries){const card=button('',()=>previewTemplate(item),'template-card'),image=document.createElement('img');image.src=item.poster_url;image.alt=item.title;image.loading='lazy';card.append(image,text('strong',item.title),text('small',item.category));target.append(card);}if(!entries.length)target.append(text('p','No templates are available right now.','muted'));}}
  function previewTemplate(item){$('preview-media').replaceChildren();const media=document.createElement(item.video_url?'video':'img');media.src=item.video_url||item.poster_url;if(item.video_url){media.controls=true;media.preload='metadata';}else media.alt=item.title;$('preview-media').append(media);$('preview-title').textContent=item.title;$('download-result').classList.add('hidden');$('use-template').classList.remove('hidden');$('use-template').onclick=()=>{
      $('preview-dialog').close();setMode({create_image:'image',text_to_video:'text',image_to_video:'photo'}[item.mode]||'image');$('prompt').value=item.prompt||'';promptCount();
      state.templateReferenceRequired=!!item.requires_reference;const rate=state.rates.find(r=>r.config.app_model_id===item.model_id&&(r.config.resolution||r.config.image_size)===item.resolution&&r.config.generated_audio===item.generated_audio);if(rate){$('model').value=modelId(rate);renderQualities();$('quality').value=rate.config.resolution||rate.config.image_size;$('sound').checked=!!item.generated_audio;renderSettings();}
      if(item.aspect_ratio)$('ratio').value=item.aspect_ratio;if(item.duration)$('duration').value=item.duration;if(item.requires_reference)$('mode-help').textContent='Add your own photo to recreate this template.';estimate();};$('preview-dialog').showModal();}
  async function loadPacks(){
    if(state.testing.enabled)return;
    const run=++packsRun,epoch=state.epoch;
    const current=()=>run===packsRun&&epoch===state.epoch;
    $('payment-error').textContent='';$('orders-error').textContent='';
    $('payment-mode').textContent='Loading current web prices...';
    await Promise.all([
      api('/app/packs/').then(catalog=>{
        if(!current())return;
        state.packs=catalog.packs;$('packs').replaceChildren();
        $('payment-mode').textContent=!catalog.enabled?'Web checkout is not enabled yet.':catalog.test?'PayU test mode: no real charges and no spendable credits are granted.':'One-time web purchases via PayU. No subscription.';
        for(const pack of catalog.packs){
          const card=text('article','','pack-card');
          card.append(text('span',pack.credits===10000?'Most popular':'ONE-TIME PACK','popular'),text('h3',pack.title),text('h2',new Intl.NumberFormat().format(pack.credits)),text('p','AI credits'),text('small','Purchased credits never expire'));
          const buy=button((catalog.test?'Test checkout - ':'Buy for ')+money(pack.amount_paise),()=>buyPack(pack),'primary');
          buy.disabled=!catalog.enabled||state.paymentBusy;card.append(buy);$('packs').append(card);
        }
        if(!catalog.packs.length)$('packs').append(text('p','No web packs are available yet. Their separate prices must be configured and synchronized on the server.','notice'));
      }).catch(error=>{if(current()){$('packs').replaceChildren();$('payment-mode').textContent='Prices could not load.';$('payment-error').textContent=error.message+' Use Refresh prices to retry.';}}),
      api('/app/payments/').then(orders=>{if(current())renderOrders(orders.orders);}).catch(error=>{if(current())$('orders-error').textContent='Purchase history could not load. '+error.message;})
    ]);
  }
  async function buyPack(pack){if(state.testing.enabled){showPage('credits');return;}if(state.paymentBusy)return;const phone=$('phone').value.trim();if(!/^\+?[0-9]{10,15}$/.test(phone)){$('payment-error').textContent='Enter your phone number for PayU checkout.';$('phone').focus();return;}state.paymentBusy=true;$('payment-error').textContent='';for(const node of $('packs').querySelectorAll('button'))node.disabled=true;
    try{let saved;try{saved=JSON.parse(savedStore.getItem('motion-web-purchase:'+state.user.id)||'null');}catch(_){}if(!saved||saved.pack!==pack.id||saved.phone!==phone)saved={pack:pack.id,phone,request_key:crypto.randomUUID()};savedStore.setItem('motion-web-purchase:'+state.user.id,JSON.stringify(saved));const data=await api('/app/payments/',{method:'POST',body:JSON.stringify(saved)});if(data.order.checkout_url)location.assign(data.order.checkout_url);else{savedStore.removeItem('motion-web-purchase:'+state.user.id);await checkOrder(data.order.id);}}
    catch(error){$('payment-error').textContent=error.message;}
    finally{state.paymentBusy=false;for(const node of $('packs').querySelectorAll('button'))node.disabled=false;}
  }
  function renderOrders(orders){$('orders').replaceChildren();for(const order of orders){const row=text('div','','order'),info=text('div','');info.append(text('strong',new Intl.NumberFormat().format(order.credits)+' credits'),text('p',money(order.amount_paise)+' · '+order.state+(order.test?' · test':'')));row.append(info,button('Check status',()=>checkOrder(order.id)));$('orders').append(row);}if(!orders.length)$('orders').append(text('p','Your verified web payments will appear here.','muted'));}
  async function checkOrder(id){try{const data=await api('/app/payments/'+encodeURIComponent(id)+'/verify/',{method:'POST',body:'{}'});if(data.order.state==='paid'){savedStore.removeItem('motion-web-purchase:'+state.user.id);toast(data.order.test?'Test payment verified. No live credits were added.':'Payment verified. Your shared credit balance is updated.');await refreshWallet();}else {if(data.order.expired||['failed','refunded'].includes(data.order.state))savedStore.removeItem('motion-web-purchase:'+state.user.id);toast(data.order.expired&&data.order.state==='pending'?'Checkout expired. Choose a pack again to start a new checkout.':'Payment status: '+data.order.state+'. Pending payments remain recoverable.');}if(state.page==='credits')await loadPacks();}catch(error){$('payment-error').textContent=error.message;}}
  document.querySelectorAll('[data-page]').forEach(node=>node.onclick=()=>showPage(node.dataset.page));
  document.querySelectorAll('[data-mode]').forEach(node=>node.onclick=()=>setMode(node.dataset.mode));
  document.querySelectorAll('[data-open-mode]').forEach(node=>node.onclick=()=>setMode(node.dataset.openMode));
  document.querySelectorAll('[data-filter]').forEach(node=>node.onclick=()=>{state.filter=node.dataset.filter;for(const tab of document.querySelectorAll('[data-filter]'))tab.classList.toggle('active',tab===node);renderProjects();});
  $('refresh-packs').onclick=()=>loadPacks();
  $('trial-feedback').onclick=()=>showFeedback({context:'credits'});
  $('feedback-form').onsubmit=event=>{event.preventDefault();saveFeedback('submitted');};
  $('feedback-skip').onclick=()=>saveFeedback('dismissed');
  $('feedback-preview').onclick=previewFeedback;
  $('feedback-dialog').addEventListener('cancel',event=>{event.preventDefault();saveFeedback('dismissed');});
  for(const dialog of document.querySelectorAll('dialog:not(#feedback-dialog):not(#visitor-welcome)'))dialog.addEventListener('close',()=>loadFeedback());
  $('model').onchange=renderQualities;$('quality').onchange=renderSettings;$('sound').onchange=()=>{const checked=$('sound').checked;renderSettings();$('sound').checked=checked;renderSettings();};$('ratio').onchange=estimate;$('duration').onchange=estimate;
  $('photos').onchange=()=>{const max=state.mode==='long'?2:state.mode==='story'?(state.rate?.config.max_reference_images||0):1;const files=[...$('photos').files];if(files.length>max){toast(`Choose up to ${max} photo(s) for this model.`);return;}if(files.some(f=>!['image/jpeg','image/png','image/webp'].includes(f.type))){toast('Choose JPG, PNG or WebP photos.');return;}state.photos=files;previews();};
  $('video').onchange=async()=>{
    const file=$('video').files[0],epoch=state.epoch,choice=++state.videoChoice;
    if(!file){state.video=null;previews();return;}
    if(file.size>200*1024*1024||file.type!=='video/mp4'){toast('Choose an MP4 reference smaller than 200 MB.');return;}
    state.videoChecking=true;$('create').disabled=true;
    try{const info=await videoInfo(file);
      if(!Number.isFinite(info.duration)||info.duration<3||info.duration>15||[info.width,info.height].some(edge=>edge<720||edge>3840))throw Error('Reference videos must be 3-15 seconds, with each edge between 720 and 3840 pixels.');
      if(epoch===state.epoch&&choice===state.videoChoice){state.video=file;previews();}
    }catch(error){if(epoch===state.epoch&&choice===state.videoChoice)toast(error.message);}
    finally{if(epoch===state.epoch&&choice===state.videoChoice){state.videoChecking=false;renderSettings();}}
  };
  $('clear-media').onclick=clearMedia;$('clear-prompt').onclick=()=>{$('prompt').value='';promptCount();};$('prompt').oninput=promptCount;
  $('inspire').onclick=()=>{$('prompt').value=state.mode==='long'?'Two adventurers discover a hidden city. They speak English. Begin with a mystery, reveal a secret and end with a hopeful discovery.':state.mode==='image'?'A cinematic portrait in a luminous futuristic city, natural facial features, violet and blue light, beautiful detail.':'A slow cinematic camera move through a golden-hour landscape, natural movement, gentle light and a sense of wonder.';promptCount();};
  $('create').onclick=beginCreate;$('quote-confirm').onclick=confirmCreate;$('quote-cancel').onclick=()=>$('quote-dialog').close();
  $('cancel-upload').onclick=()=>{if(state.submission){toast('The submission may already be saved. Recover it using the same request.');return;}state.createRun++;uploadRequest?.abort();for(const request of state.requests)request.abort();setBusy(false);};
  $('recover-create').onclick=async()=>{if(state.busy)return;setBusy(true);try{await submitSaved();}catch(error){$('create-error').textContent=error.message;}finally{setBusy(false);}};
  $('balance').onclick=()=>showPage('credits');$('template-search').oninput=renderTemplates;$('refresh-orders').onclick=()=>loadPacks().catch(error=>$('payment-error').textContent=error.message);
  $('refresh-projects').onclick=()=>refreshProjects(false).catch(error=>$('projects-error').textContent=error.message);$('more-projects').onclick=()=>refreshProjects(true).catch(error=>$('projects-error').textContent=error.message);
  $('close-preview').onclick=()=>$('preview-dialog').close();$('preview-dialog').addEventListener('close',()=>{for(const video of $('preview-media').querySelectorAll('video')){video.pause();video.removeAttribute('src');video.load();}$('preview-media').replaceChildren();});
  $('signout').onclick=async()=>{try{await api('/app/logout/',{method:'POST',body:'{}'});window.google?.accounts?.id?.disableAutoSelect();try{localStorage.removeItem('motion-web-account');}catch(_){}forgetAccount();await initialize();}catch(error){toast(error.message);}};
  $('mobile-signout').onclick=()=>$('signout').click();
  let theme;try{theme=localStorage.getItem('motion-web-theme');}catch(_){}if(theme==='light')document.documentElement.dataset.theme='light';
  $('theme').onclick=()=>{const value=document.documentElement.dataset.theme==='light'?'dark':'light';document.documentElement.dataset.theme=value;try{localStorage.setItem('motion-web-theme',value);}catch(_){}};
  document.addEventListener('visibilitychange',()=>{if(!document.hidden&&state.user){refreshWallet().catch(()=>{});schedulePoll();}});
  window.addEventListener('storage',event=>{if(event.key==='motion-web-account'&&state.user&&event.newValue!==state.user.id){forgetAccount();initialize();}});
  initialize();
})();
