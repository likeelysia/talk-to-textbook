const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
function fn(name) {
  const match = source.match(new RegExp('^(?:async )?function ' + name + '\\([^]*?^}', 'm'));
  if (!match) throw new Error('Missing function ' + name);
  return match[0];
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a;reject=b; }); return {promise,resolve,reject}; };
function setup(fetch) {
  const writes = [], nodes = [], handlers = {}, redirects=[], ids={};
  function element() { const e={textContent:'',innerHTML:'',value:'',hidden:false,disabled:false,tagName:'DIV',className:'',classList:{add(){},remove(){}},closest(){return null;},replaceChildren(){this.innerHTML='';this.textContent='';},querySelector(){return element();}};nodes.push(e);return e; }
  const byId=id=>ids[id]||(ids[id]=element());
  const controls=[byId('send'),byId('question')];controls[0].tagName='BUTTON';controls[1].tagName='TEXTAREA';
  const c = vm.createContext({window:{fetch,addEventListener:(name,fn)=>handlers[name]=fn},document:{cookie:'',visibilityState:'visible',body:{classList:{add(){},remove(){}}},getElementById:byId,querySelectorAll:()=>controls},
    localStorage:{getItem(){return null;},removeItem(){},setItem:(key,value)=>writes.push({key,value})},
    location:{pathname:'/study.html',href:''},TextDecoder,TextEncoder,AbortController,FormData,setTimeout,clearTimeout,console,
    authEpoch:1,authUserId:'A',identityReady:true,CONV_STORAGE_KEY:'fa.conversationId.A',currentConversationId:'default',streamCtrl:null,activeUserOp:null,
    messagesEl:byId('messages'),coreTextEl:byId('coreText'),coreCountEl:byId('coreCount'),noteSelectMode:false,noteSelected:new Set(),currentNoteId:null,addBubble:()=>element(),renderStoredMessage:(m)=>writes.push({render:m.content}),loadMemoryCount(){},
    initConversations:()=>writes.push({reloadConversations:true}),loadKnowledge:()=>writes.push({reloadKnowledge:true}),GREETING:'greeting',escapeHtml:x=>x,readError:async()=>'',csrfToken:()=>'',redirectToLogin:()=>redirects.push('/login.html'),
    setStreaming:value=>writes.push({streaming:value}),renderAnswer:x=>JSON.stringify(x),renderRichText:x=>x,scrollToBottom(){},
    fallBackToDefaultConversation:async()=>writes.push({fallback:true}),STREAM_RENDER_THROTTLE_MS:150,STREAM_RENDER_MIN_CHARS:12});
  const identityClass=source.match(/^class IdentityChangedError[^]*?^}/m);if(!identityClass)throw new Error('Missing identity error class');vm.runInContext(identityClass[0],c);
  for(const name of ['beginUserOp','opAlive','clearPrivateDom','bumpAuthEpoch','notifyAuthChange','initUserStorage','authFetch','apiJson','loadThread','parseSseEvent','readSse','countOf','safeToRenderPartial','answerByStream','askText','askImages','recheckIdentity','loadCoreMemory']) vm.runInContext(fn(name),c);
  const begin=source.indexOf("window.addEventListener('pageshow'");
  const end=source.indexOf('\nloadKnowledge();',begin);
  if(begin<0 || end<0)throw new Error('Missing pageshow source');
  vm.runInContext(source.slice(begin,end),c);
  return {c,writes,nodes,handlers,redirects,ids,byId,controls};
}
const result=[];
async function main() {
  {
    let requests=0;const h=setup(async()=>{requests++;return {status:200,ok:true};});
    h.c.identityReady=false;
    await h.c.authFetch('/api/conversations',{method:'POST'}).catch(()=>{});
    result.push({test:'identity_invalid_blocks_request_before_send',passed:requests===0,requests_sent:requests});
  }
  {
    const d=deferred();const h=setup(()=>d.promise);const pending=h.c.authFetch('/synthetic').then(()=>false,()=>true);
    h.c.bumpAuthEpoch();d.resolve({status:200,ok:true});
    result.push({test:'changed_epoch_rejects_late_headers',passed:await pending});
  }
  {
    const body=deferred(), started=deferred();const h=setup(async()=>({status:200,ok:true,json:()=>{started.resolve();return body.promise;}}));
    const pending=h.c.loadThread('default');await started.promise;
    h.c.bumpAuthEpoch();h.c.authUserId='B';h.c.identityReady=true;h.c.CONV_STORAGE_KEY='fa.conversationId.B';
    body.resolve({messages:[{role:'user',content:'SYNTHETIC_A_PRIVATE_HISTORY'}]});await pending;
    const oldWrites=h.writes.filter(x=>x.render==='SYNTHETIC_A_PRIVATE_HISTORY').length;
    result.push({test:'old_json_body_cannot_render_after_switch',passed:oldWrites===0,old_history_rendered:oldWrites});
  }
  {
    const chunk=deferred(), reading=deferred();let n=0;
    const body={getReader:()=>({read:()=>{if(n++===0){reading.resolve();return chunk.promise;}return Promise.resolve({done:true});}})};
    const h=setup(async()=>({status:200,ok:true,body}));const pending=h.c.answerByStream('synthetic');await reading.promise;
    h.c.bumpAuthEpoch();h.c.authUserId='B';h.c.identityReady=true;
    const replacement=new AbortController();h.c.streamCtrl=replacement;
    chunk.resolve({done:false,value:new TextEncoder().encode('event: delta\ndata: {"text":"SYNTHETIC_A_PRIVATE_STREAM"}\n\nevent: done\ndata: {"answer":"SYNTHETIC_A_PRIVATE_STREAM"}\n\n')});
    await pending;
    const staleDom=h.nodes.some(e=>String(e.textContent).includes('SYNTHETIC_A_PRIVATE_STREAM')||String(e.innerHTML).includes('SYNTHETIC_A_PRIVATE_STREAM'));
    const controllerPreserved=h.c.streamCtrl===replacement;
    result.push({test:'old_sse_cannot_render_after_epoch_change',passed:!staleDom,old_stream_wrote_to_node:staleDom});
    result.push({test:'old_finally_preserves_new_stream_controller',passed:controllerPreserved,new_stream_controller_preserved:controllerPreserved});
  }
  {
    const first=deferred();let n=0;
    const h=setup(()=>++n===1?first.promise:Promise.resolve({ok:true,json:async()=>({authenticated:true,userId:'A'})}));
    h.c.authUserId=null;h.c.identityReady=false;
    const init=h.c.initUserStorage();h.handlers.pageshow({persisted:false});await tick();
    first.resolve({ok:true,json:async()=>({authenticated:true,userId:'A'})});await init;
    result.push({test:'initial_pageshow_does_not_redirect_valid_pending_identity',passed:h.redirects.length===0,redirects:h.redirects.length,identity_ready_after_init:h.c.identityReady});
  }
  {
    const h=setup(async()=>{throw new Error('synthetic unavailable');});h.c.messagesEl.innerHTML='SYNTHETIC_OLD_PRIVATE_DOM';
    h.handlers.pageshow({persisted:true});await tick();
    result.push({test:'failed_restore_validation_keeps_private_state_inactive',passed:h.c.identityReady===false,identity_ready:h.c.identityReady,old_private_dom_retained:h.c.messagesEl.innerHTML.includes('SYNTHETIC_OLD_PRIVATE_DOM')});
  }
  {
    const body=deferred(), started=deferred();const h=setup(async()=>({status:200,ok:true,json:()=>{started.resolve();return body.promise;}}));
    const pending=h.c.loadThread('default');await started.promise;h.c.bumpAuthEpoch();
    h.c.authUserId='B';h.c.identityReady=true;h.c.messagesEl.innerHTML='SYNTHETIC_B_HISTORY';
    body.resolve({messages:[{content:'SYNTHETIC_A_HISTORY'}]});await pending;
    result.push({test:'old_json_error_does_not_erase_new_identity_dom',passed:h.c.messagesEl.innerHTML==='SYNTHETIC_B_HISTORY',new_history_preserved:h.c.messagesEl.innerHTML==='SYNTHETIC_B_HISTORY'});
  }
  {
    const body=deferred(),started=deferred();const h=setup(async()=>({ok:true,json:()=>{started.resolve();return body.promise;}}));
    h.c.identityReady=false;h.c.authUserId=null;
    const pending=h.c.initUserStorage();await started.promise;h.c.bumpAuthEpoch();
    body.resolve({authenticated:true,userId:'A'});await pending;
    result.push({test:'old_identity_init_cannot_reactivate_after_invalidation',passed:!h.c.identityReady,identity_ready:h.c.identityReady,user_id:h.c.authUserId});
  }
  {
    const h=setup(async()=>({}));h.byId('coreText').tagName='TEXTAREA';h.byId('coreText').value='SYNTHETIC_A_CORE';
    h.byId('noteDetail').innerHTML='SYNTHETIC_A_NOTE';h.byId('noteModal').hidden=false;
    h.c.bumpAuthEpoch();const coreRetained=h.byId('coreText').value==='SYNTHETIC_A_CORE',noteRetained=h.byId('noteDetail').innerHTML==='SYNTHETIC_A_NOTE';
    result.push({test:'invalidation_clears_core_and_open_note',passed:!coreRetained&&!noteRetained,core_retained:coreRetained,note_retained:noteRetained,note_modal_still_open:!h.byId('noteModal').hidden});
  }
  {
    const body=deferred();const h=setup(()=>body.promise);h.c.messagesEl.innerHTML='SYNTHETIC_A_DOM';
    h.handlers.pageshow({persisted:true});const remainsReady=h.c.identityReady;
    result.push({test:'pending_restore_validation_pauses_private_state',passed:!remainsReady,identity_ready_while_pending:remainsReady,old_dom_retained_while_pending:h.c.messagesEl.innerHTML==='SYNTHETIC_A_DOM'});
    body.resolve({ok:true,json:async()=>({authenticated:true,userId:'A'})});await tick();
  }
  {
    const body=deferred(),started=deferred();const h=setup(async()=>({status:200,ok:true,json:()=>{started.resolve();return body.promise;}}));
    const pending=h.c.askImages([],'synthetic').then(value=>({returned:true,value}),()=>({returned:false}));await started.promise;
    h.c.bumpAuthEpoch();h.c.authUserId='B';h.c.identityReady=true;body.resolve({answer:'SYNTHETIC_A_IMAGE_ANSWER'});
    const returned=await pending;result.push({test:'old_image_json_rejected_after_identity_change',passed:!returned.returned,old_image_answer_returned:returned.returned});
  }
  {
    const h=setup(async()=>({ok:true,json:async()=>({authenticated:true,userId:'A'})}));
    await h.c.recheckIdentity();
    result.push({test:'same_identity_restore_reenables_private_controls',passed:h.c.identityReady&&h.controls.every(e=>!e.disabled),identity_ready:h.c.identityReady,send_disabled:h.byId('send').disabled,input_disabled:h.byId('question').disabled});
  }
  {
    const body=deferred(),started=deferred(),validation=deferred();let n=0;
    const h=setup(()=>++n===1?Promise.resolve({status:200,ok:true,json:()=>{started.resolve();return body.promise;}}):validation.promise);
    const pending=h.c.loadThread('default');await started.promise;const checking=h.c.recheckIdentity();
    body.resolve({messages:[{role:'user',content:'SYNTHETIC_OLD_PRIVATE_DURING_RECHECK'}]});await pending;
    const wrote=h.writes.some(x=>x.render==='SYNTHETIC_OLD_PRIVATE_DURING_RECHECK');
    result.push({test:'old_json_cannot_repaint_during_pending_identity_recheck',passed:!wrote,old_render_while_identity_not_ready:wrote,identity_ready_when_rendered:h.c.identityReady});
    validation.resolve({ok:true,json:async()=>({authenticated:true,userId:'A'})});await checking;
  }
  {
    const body=deferred(),started=deferred();const h=setup(async()=>({status:200,ok:true,json:()=>{started.resolve();return body.promise;}}));
    const pending=h.c.loadCoreMemory();await started.promise;h.c.bumpAuthEpoch();h.c.authUserId='B';h.c.identityReady=true;h.c.coreCountEl.textContent='SYNTHETIC_B_COUNT';
    body.resolve({text:'SYNTHETIC_A_CORE',maxChars:100});await pending;
    result.push({test:'old_core_error_does_not_overwrite_new_identity_state',passed:h.c.coreCountEl.textContent==='SYNTHETIC_B_COUNT',new_core_count_preserved:h.c.coreCountEl.textContent==='SYNTHETIC_B_COUNT'});
  }
  {
    const h=setup(async()=>({status:200,ok:true,json:async()=>({answer:'SYNTHETIC_NORMAL_IMAGE'})}));const answer=await h.c.askImages([],'synthetic');
    result.push({test:'normal_image_json_still_succeeds',passed:answer.answer==='SYNTHETIC_NORMAL_IMAGE'});
  }
  {
    const h=setup(async()=>({ok:true,json:async()=>({authenticated:true,userId:'A'})}));h.c.identityReady=false;h.c.authUserId=null;
    const ok=await h.c.initUserStorage();result.push({test:'normal_initial_identity_still_succeeds',passed:ok&&h.c.identityReady&&h.c.authUserId==='A'});
  }
  fs.writeFileSync(path.join(__dirname,'frontend-results.json'),JSON.stringify({source_commit:'52f42d4',method:'Node VM executes verbatim selected app.js functions with controlled deferred responses and minimal DOM substitutes; not a real browser test',results:result},null,2));
  console.log(JSON.stringify(result,null,2));
}
const timeout=setTimeout(()=>{console.error('Probe did not complete controlled scenarios');process.exit(2);},6000);
main().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>clearTimeout(timeout));
