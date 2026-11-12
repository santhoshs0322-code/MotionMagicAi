'use strict';
(() => {
  const dialog=document.getElementById('visitor-welcome');if(!dialog)return;
  const form=document.getElementById('visitor-welcome-form'),skip=document.getElementById('visitor-welcome-skip'),submit=document.getElementById('visitor-welcome-submit'),error=document.getElementById('visitor-welcome-error');
  let csrf='',onClose=null,busy=false,opened=false;
  function open(token,callback){if(opened||document.querySelector('dialog[open]'))return;opened=true;csrf=token;onClose=callback;dialog.showModal();}
  async function save(outcome){
    if(busy||outcome==='submitted'&&!form.reportValidity())return;
    busy=true;skip.disabled=true;submit.disabled=true;error.textContent='';
    const data={outcome};if(outcome==='submitted'){const fields=new FormData(form);data.interest=fields.get('interest');data.goal=fields.get('goal')||'';}
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
    try{const response=await fetch('/app/visitor-interest/',{method:'POST',credentials:'same-origin',signal:controller.signal,
      headers:{'Content-Type':'application/json','X-CSRFToken':csrf,'Accept':'application/json'},body:JSON.stringify(data)});
      const result=await response.json();if(!response.ok||!result.ok)throw Error(result.message||'Your response could not be saved.');
      form.reset();dialog.close();onClose?.();
    }catch(problem){if(outcome==='dismissed'){form.reset();dialog.close();onClose?.();}else error.textContent=problem.name==='AbortError'?'Saving took too long. Retry, or choose Not now.':problem.message;}
    finally{clearTimeout(timer);busy=false;skip.disabled=false;submit.disabled=false;}
  }
  form.onsubmit=event=>{event.preventDefault();save('submitted');};skip.onclick=()=>save('dismissed');
  dialog.addEventListener('cancel',event=>{event.preventDefault();save('dismissed');});
  window.MotionWelcome={open};
  if(dialog.dataset.auto==='true'&&dialog.dataset.needed==='true')open(dialog.dataset.csrf);
})();
