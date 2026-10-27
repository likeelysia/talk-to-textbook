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
    location:{pathname:'/study.html',href:''},TextDecoder,TextEncoder,AbortController,FormData,setInterval:()=>1,clearInterval:()=>{},setTimeout,clearTimeout,console,
    authEpoch:1,authUserId:'A',identityReady:true,CONV_STORAGE_KEY:'fa.conversationId.A',currentConversationId:'default',streamCtrl:null,activeUserOp:null,
    messagesEl:byId('messages'),coreTextEl:byId('coreText'),coreCountEl:byId('coreCount'),noteSelectMode:false,noteSelected:new Set(),currentNoteId:null,addBubble:()=>element(),renderStoredMessage:(m)=>writes.push({render:m.content}),loadMemoryCount(){},
    initConversations:()=>writes.push({reloadConversations:true}),loadKnowledge:()=>writes.push({reloadKnowledge:true}),GREETING:'greeting',escapeHtml:x=>x,readError:async()=>'',csrfToken:()=>'',redirectToLogin:()=>redirects.push('/login.html'),
    setStreaming:value=>writes.push({streaming:value}),renderAnswer:x=>JSON.stringify(x),renderRichText:x=>x,scrollToBottom(){},
    fallBackToDefaultConversation:async()=>writes.push({fallback:true}),STREAM_RENDER_THROTTLE_MS:150,STREAM_RENDER_MIN_CHARS:12});
  const identityClass=source.match(/^class IdentityChangedError[^]*?^}/m);if(!identityClass)throw new Error('Missing identity error class');vm.runInContext(identityClass[0],c);
  for(const name of ['beginUserOp','opAlive','clearPrivateDom','bumpAuthEpoch','initUserStorage','authFetch','apiJson','loadThread','parseSseEvent','readSse','countOf','safeToRenderPartial','answerByStream','askText','askImages','recheckIdentity','loadCoreMemory','answerByImages']) vm.runInContext(fn(name),c);
  const begin=source.indexOf("window.addEventListener('pageshow'");
  const end=source.indexOf('\nloadKnowledge();',begin);
  if(begin<0 || end<0)throw new Error('Missing pageshow source');
  vm.runInContext(source.slice(begin,end),c);
  return {c,writes,nodes,handlers,redirects,ids,byId,controls};
}
async function main(){
  const results=[];
  for(const invalidate of [true,false]){
  const wait=deferred(),started=deferred(),h=setup(async()=>({})),reload=[];
  h.c.formEl={addEventListener:(name,handler)=>h.handlers[name]=handler};
  h.c.questionEl=h.byId('question');h.c.questionEl.value='synthetic';
  h.c.picked=[{file:{}}];h.c.bubbleThumbsHtml=()=>'';h.c.renderPreviews=()=>{};h.c.setHint=()=>{};
  h.c.answerByImages=async()=>{started.resolve();await wait.promise;};
  h.c.loadConversations=id=>reload.push(id);
  const begin=source.indexOf("formEl.addEventListener('submit'");const end=source.indexOf('\n});',begin)+5;
  if(begin<0||end<5)throw new Error('missing submit handler');
  vm.runInContext(source.slice(begin,end),h.c);
  const old=h.handlers.submit({preventDefault(){}});await started.promise;
  if(invalidate){h.c.bumpAuthEpoch();h.c.authUserId='B';h.c.identityReady=true;h.c.currentConversationId='SYNTHETIC_B_CONVERSATION';}
  wait.resolve();await old;
  results.push({test:invalidate?'old_outer_submit_finally_does_not_reload_new_identity':'normal_outer_submit_still_refreshes_conversations',passed:invalidate?reload.length===0:reload.length===1&&reload[0]==='default',loadConversations_calls:reload});
  }
  const evidence={method:'verbatim actual submit handler, controlled inner image operation, shared reload spy; not a real browser test',results};
  fs.writeFileSync(path.join(__dirname,'submit-finally-results.json'),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence,null,2));
}
const timeout=setTimeout(()=>{console.error('probe timeout');process.exit(2);},6000);
main().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>clearTimeout(timeout));
