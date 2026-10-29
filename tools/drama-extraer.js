/* drama-extraer.js — captura los canales de DramaLive DESDE TU PROPIA SESION.
   Medido 2026-10-08: el API (main.fastconnections.online/api/live/livedrama/v13.0.0/
   {a,glt,gs}) responde a todo payload anonimo los mismos 32 bytes de relleno PHP
   ("\t\t\r\n+espacios"), y mikkm.xyz/android/androidApi.php dice
   "ACCESS DENIED! AndroidAPI v3.1". Las credenciales embebidas del bundle NO se
   usan (acuerdo explicito). La via legitima es tu navegador, que YA es una sesion:
   pega esto en la Consola de web.dramalive.cc (o inyectalo por CDP con
   Runtime.evaluate). Que hace:
     1. escucha fetch() y XMLHttpRequest hacia la API del sitio;
     2. descifra el alambre del cliente: base64(AES-CBC) ":" base64(iv) con la key
        literal del bundle 43fc9b1a3f854049 (AES-128-CBC; WebCrypto quita PKCS7);
     3. apila todo objeto-canal (nombre + url, o server+path -> formula medida
        https://{server}/{path}/index.html) en una tabla deduplicada, sin depender
        del esquema exacto (resiste v13.0.x);
     4. DramaExport.m3u() / DramaExport.json() descargan; el m3u se pega en la app
        (En vivo -> Fuente -> Pegar lista) y cada index.html reproduce como HLS.
   Y ademas: al ver la primera peticion glt/gs aprende la PLANTILLA de campos del
   cliente, asi que DramaExport.replay('glt', {type:'sports'}) enumera categorias
   sin scrollear el grid Flutter (el bloqueo nº1 del plan por CDP). */
(function(){
'use strict';
if (window.DramaExport) { console.log('[drama] ya instalado'); return; }
var KEY='43fc9b1a3f854049', HINT=/fastconnections\.online\/api|androidApi\.php|livedrama\/v/i;
var cap={filas:[],vis:Object.create(null),plant:{},n:0};
var DramaExport=window.DramaExport={};
function kb(){var o=new Uint8Array(16);for(var i=0;i<16;i++)o[i]=parseInt(KEY.substr(i*2,2),16);return o}
var kk=crypto.subtle.importKey('raw',kb(),{name:'AES-CBC'},false,['decrypt','encrypt']);
function b2b(s){s=String(s).replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';
var x=atob(s),u=new Uint8Array(x.length);for(var i=0;i<x.length;i++)u[i]=x.charCodeAt(i);return u}
function p2b(u){var s='';for(var i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);return btoa(s)}
async function dec(txt){
if(typeof txt!=='string')return null;txt=txt.trim();
if(txt.charCodeAt(0)===123||txt.charCodeAt(0)===91)return txt;
var i=txt.lastIndexOf(':');
if(i>24&&txt.length>64){
var ct=txt.slice(0,i),iv=txt.slice(i+1);
if(/^[A-Za-z0-9+\/=_-]+$/.test(ct)&&/^[A-Za-z0-9+\/=_-]{16,32}$/.test(iv)){
try{var k=await kk;
var pt=await crypto.subtle.decrypt({name:'AES-CBC',iv:b2b(iv)},k,b2b(ct));
return new TextDecoder('utf-8',{fatal:false}).decode(pt);}catch(e){}}}
return null}
async function enc(obj){
var k=await kk,iv=crypto.getRandomValues(new Uint8Array(16));
var ct=await crypto.subtle.encrypt({name:'AES-CBC',iv:iv},k,new TextEncoder().encode(JSON.stringify(obj)));
return p2b(new Uint8Array(ct))+':'+p2b(iv)}
function nom(o){return o.name||o.title||o.channel_name||o.cname||o.tvg_name||o.label||o.channel}
function urlDe(o){
var u=o.url||o.link||o.href||o.stream_url||null;
if(u)return String(u);
var s=String(o.server||o.server_url||'').replace(/^https?:\/\//,'').replace(/\/+$/,'');
if(!s)return null;
var p=String(o.path||o.stream_path||'').replace(/^\/+/,'').replace(/\/+$/,'');
return 'https://'+s+'/'+p+(/index\.(html|m3u8)$/.test(p)?'':'/index.html')}
function ver(o,origen){
if(!o||typeof o!=='object')return;
if(Array.isArray(o)){for(var i=0;i<o.length;i++)ver(o[i],origen);return}
var n=nom(o),u=urlDe(o);
if(n&&u){n=String(n).trim();var k=JSON.stringify([n,u]);
if(!cap.vis[k]){cap.vis[k]=1;cap.filas.push({n:n,u:u,
g:String(o.category||o.group||o.group_title||o.live_category||o.type||"DramaLive"),
l:o.logo||o.tvg_logo||o.image||null,s:origen||""});}
return}
for(var j in o){try{ver(o[j],origen)}catch(e){}}}
function trafico(ep,bodyTxt,decTxt){
cap.n++;
if(!bodyTxt)return;
var m=String(bodyTxt).match(/([a-z]{2,10})$/i);
try{var o=JSON.parse(decTxt);
if(o&&typeof o==='object'&&!Array.isArray(o)&&typeof o.type==='string'||o&&o.type)
{cap.plant[ep]=o;console.log("[drama] plantilla de",ep,"capturada — replay() disponible")}
}catch(e){}}
var of=window.fetch;
window.fetch=function(u,opt){
var url=String(u&&u.url?u.url:u);
var t=API_HIT(url);
var body=opt&&opt.body?String(opt.body):null;
var p=of.apply(this,arguments);
if(t&&body){dec(body).then(function(cl){if(!cl)return;try{var o=JSON.parse(cl);
if(o&&typeof o==='object'&&!Array.isArray(o)){var ep=(url.split('/').pop()||'').slice(0,10);
cap.plant[ep]=o;console.log('[drama] plantilla '+ep+' capturada (replay listo)')}}catch(e){}})}
if(t)p.then(function(r){try{r.clone().text().then(function(x){dec(x).then(function(claro){
if(claro){var o=null;try{o=JSON.parse(claro)}catch(e){}
if(o)ver(o,"resp:"+url.split('/').pop());trafico((url.split('/').pop()||"").slice(0,10),body,claro)}})})}catch(e){}});
return p};
function API_HIT(u){return HINT.test(u)}
var OX=window.XMLHttpRequest;
window.XMLHttpRequest=function(){
var x=new OX(),op=x.open,self=this;
x.open=function(mth,uu){this.__u=String(uu);this.__m=mth;return op.apply(x,arguments)};
var SN=x.send;
x.send=function(b){
var t=API_HIT(x.__u||"");
if(t&&b){dec(String(b)).then(function(cl){if(!cl)return;try{var o=JSON.parse(cl);
if(o&&typeof o==='object'&&!Array.isArray(o)){var ep=((x.__u||'').split('/').pop()||'').slice(0,10);
cap.plant[ep]=o;console.log('[drama] plantilla '+ep+' capturada (XHR)')}}catch(e){}})}
if(t){x.addEventListener("loadend",function(){
try{var txt=x.responseText;if(!txt)return;
dec(txt).then(function(claro){if(!claro)return;
var o=null;try{o=JSON.parse(claro)}catch(e){}
if(o){ver(o,"resp:"+((x.__u||"").split("/").pop()||"xhr"));
trafico(((x.__u||"").split("/").pop()||"").slice(0,10),b&&String(b),claro)}})}catch(e){}})}
return SN.apply(x,arguments)}};
DramaExport.tabla=function(){console.table(cap.filas.map(function(f){return {canal:f.n,categoria:f.g,url:f.u,via:f.s}}));return cap.filas};
DramaExport.n=function(){return cap.filas.length};
async function replay(ep,extra){
var base=cap.plant[ep]||cap.plant.glt||cap.plant.gs;
if(!base){console.warn("[drama] sin plantilla: navega un momento por la app (abre Live) y reintenta");return null}
var q=JSON.parse(JSON.stringify(base));for(var k in(extra||{}))q[k]=extra[k];
var r=await of.call(window,API_BASE()+ep,{method:"POST",headers:{"Content-Type":"text/plain; charset=utf-8"},body:await enc(q)});
var txt=await r.text(),claro=await dec(txt);
console.log("[drama] replay",ep,JSON.stringify(extra||{}),"->",claro?claro.length+" B (descifrado)":r.status+" sin descifrar");
if(claro){var o=null;try{o=JSON.parse(claro)}catch(e){}if(o)ver(o,"replay:"+ep)}
return claro}
function API_BASE(){
var s=(typeof document!=="undefined")&&document.querySelector("meta[name=drama-api-base]");
if(s)return s.content;
return "https://main.fastconnections.online/api/live/livedrama/v13.0.0/"}
DramaExport.replay=function(ep,extra){return replay(ep,extra)};
DramaExport.m3u=function(){
var porGrupo={};
cap.filas.forEach(function(f){(porGrupo[f.g]=porGrupo[f.g]||[]).push(f)});
var out=["#EXTM3U"];
Object.keys(porGrupo).sort().forEach(function(g){
porGrupo[g].sort(function(a,b){return a.n<b.n?-1:1}).forEach(function(f){
out.push('#EXTINF:-1 tvg-name="'+f.n.replace(/"/g,"'")+'" group-title="'+g.replace(/"/g,"'")+
(f.l?'" tvg-logo="'+f.l:'"')+","+f.n.replace(/"/g,"'"));
out.push(f.u)})});
var txt=out.join("\n")+"\n";
descargar("canales-drama.m3u",txt,"audio/x-mpegurl");
console.log("[drama] m3u con",cap.filas.length,"canales /",Object.keys(porGrupo).length,"grupos");
return txt};
DramaExport.json=function(){var t=JSON.stringify({extraido:new Date().toISOString(),canales:cap.filas},null,1);
descargar("canales-drama.json",t,"application/json");
console.log("[drama] json con",cap.filas.length,"canales — convierte offline con tools/drama-extraer.py m3u canales-drama.json");
return t};
function downloading(nombre,txt,tipo){
var a=document.createElement("a");a.href=URL.createObjectURL(new Blob([txt],{type:tipo}));
a.download=nombre;document.body.appendChild(a);a.click();setTimeout(function(){a.remove()},400)}
var descargar=downloading;
if(typeof document!=="undefined"){var bot=document.createElement("div");
bot.style.cssText="position:fixed;right:12px;bottom:12px;z-index:999999;background:#0D0F14;color:#42D1FF;border:1px solid #42D1FF;border-radius:10px;padding:10px 12px;font:13px sans-serif;display:flex;gap:8px;align-items:center";
bot.innerHTML='<span id="drc">0 canales / '+cap.n+' req</span>'+
'<button id="drg">glt:live</button><button id="drj">.m3u</button><button id="drf">.json</button>';
document.body.appendChild(bot);
setInterval(function(){var e=document.getElementById("drc");if(e)e.textContent=cap.filas.length+" canales / "+cap.n+" req"},900);
document.getElementById("drg").onclick=function(){replay("glt",{type:"live"}).then(function(){
["live","sports","kids","news","movies","series"].forEach(function(t,i){setTimeout(function(){replay("glt",{type:t})},900*(i+1))})})};
document.getElementById("drj").onclick=function(){DramaExport.json()};
document.getElementById("drf").onclick=function(){DramaExport.m3u()};
console.log("%c[drama] listo: navega Live y el boton glt:live enumera. DramaExport.tabla() / .m3u() / .json() / .replay(ep,{campos})","color:#42D1FF");
}
})();
