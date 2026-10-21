const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function app(consented=true){
 const nodes=new Map(),storage=new Map(consented?[['mf.consent','1']]:[]),searches=[];
 const make=()=>({value:'',textContent:'',hidden:false,children:[],classList:{toggle(){}},append(...items){this.children.push(...items);},replaceChildren(){this.children=[];},setAttribute(){},scrollIntoView(){},showModal(){this.open=true;},close(){this.open=false;}});
 const listeners={};const document={body:make(),getElementById(id){if(!nodes.has(id))nodes.set(id,make());return nodes.get(id);},createElement:make,querySelectorAll(){return [];},addEventListener(name,callback){listeners[name]=callback;}};
 const context={document,location:{origin:'https://com.musicflow.app'},localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},console,URL,URLSearchParams,setTimeout:(f,ms)=>{const timer=setTimeout(f,ms);timer.unref();return timer;},clearTimeout};
 context.window=context;context.MusicFlowCore=require('../app/src/main/assets/core.js');context.MusicFlowAndroid={searchYouTube:q=>searches.push(q)};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8'),context);
 const form=document.getElementById('searchForm');form.requestSubmit=()=>form.onsubmit({preventDefault(){}});
 return {context,document,searches,storage,listeners};
}

test('Artist search uses native catalog without key; stale results cannot replace newest search',()=>{
 const a=app(),q=a.document.getElementById('query'),form=a.document.getElementById('searchForm');
 q.value='Bad Bunny';form.requestSubmit();q.value='Salsa';form.requestSubmit();assert.deepEqual(a.searches,['Bad Bunny','Salsa']);
 a.context.receiveSearchResults(1,[{id:'abcdefghijk',title:'Old'}],'');assert.equal(a.document.getElementById('list').children.length,1);
 a.context.receiveSearchResults(2,[{id:'12345678901',title:'New'}],'');assert.match(a.document.getElementById('status').textContent,/1 resultados/);
 assert.equal(a.document.getElementById('list').children[0].children[1].children[0].textContent,'New');
});
test('Consent resumes the search',()=>{
 const a=app(false);a.document.getElementById('query').value='Salsa';a.document.getElementById('searchForm').requestSubmit();assert.equal(a.searches.length,0);a.document.getElementById('acceptPrivacy').onclick();assert.deepEqual(a.searches,['Salsa']);a.context.receiveSearchResults(1,[],'');
});
test('Online results play through native service, mixed queue preserved, hiding does not pause',()=>{
 const a=app(),plays=[],commands=[];a.context.MusicFlowAndroid.playAudio=(id,queue)=>plays.push([id,JSON.parse(queue)]);a.context.MusicFlowAndroid.audioControl=(cmd,value)=>commands.push([cmd,value]);
 a.document.getElementById('query').value='Music';a.document.getElementById('searchForm').requestSubmit();
 const tracks=[{id:'abcdefghijk',title:'One',channel:'Artist'},{id:'12345678901',title:'Two',channel:'Artist'}];a.context.receiveSearchResults(1,tracks,'');
 a.document.getElementById('list').children[0].children[1].onclick();assert.deepEqual(plays[0],['abcdefghijk',['abcdefghijk','12345678901']]);
 a.context.receiveAudioState({active:true,track:tracks[0],playing:true,index:0,size:2,position:2000,duration:10000});
 a.context.pauseMusic();assert.equal(commands.length,0);assert.equal(a.document.getElementById('audioElapsed').textContent,'0:02');
 a.document.getElementById('nextTrack').onclick();assert.equal(commands[0][0],'next');
 a.document.getElementById('togglePlayback').onclick();assert.equal(commands[1][0],'toggle');
 a.document.getElementById('audioSeek').value=6000;a.document.getElementById('audioSeek').onchange();assert.deepEqual(commands[2],['seek',6000]);
 a.document.getElementById('nowFavorite').onclick();assert.equal(JSON.parse(a.storage.get('mf.library')).favorites[0].id,tracks[0].id);
});
test('Catalog failures are visible and do not pretend there are no matches',()=>{
 const a=app();a.document.getElementById('query').value='Music';a.document.getElementById('searchForm').requestSubmit();a.context.receiveSearchResults(1,[],'Sin conexión');assert.equal(a.document.getElementById('status').textContent,'Sin conexión');
});
test('Unavailable local audio from backup is not sent to player',()=>{
 const a=app(),plays=[];a.context.MusicFlowAndroid.playAudio=id=>plays.push(id);
 a.context.receiveAudioState({active:true,track:{id:'audio-00000000-0000-4000-8000-000000000001',kind:'audio',title:'Missing'},playing:true,index:0,size:1});
 a.context.receiveAudioState({active:false});a.document.getElementById('togglePlayback').onclick();assert.equal(plays.length,0);assert.match(a.document.getElementById('status').textContent,/Vuelve a importar/);
});

test('History is persisted, deduplicated and clearable',()=>{
 const a=app();for(const q of ['Salsa','Rock','salsa']){a.document.getElementById('query').value=q;a.document.getElementById('searchForm').requestSubmit();}
 assert.deepEqual(JSON.parse(a.storage.get('mf.history')),['salsa','Rock']);
 a.document.getElementById('clearHistory').onclick();assert.equal(a.storage.has('mf.history'),false);
 a.context.receiveSearchResults(3,[],'');
});
test('More results append without duplicates and retain rows on error',()=>{
 const a=app(),calls=[];a.context.MusicFlowAndroid.searchCatalog=(...args)=>calls.push(args);
 a.document.getElementById('query').value='Salsa';a.document.getElementById('searchForm').requestSubmit();
 const one={id:'abcdefghijk',title:'One'},two={id:'12345678901',title:'Two'};
 a.context.receiveSearchResults(1,[one],'',{next:'next-token'});a.document.getElementById('moreResults').onclick();
 assert.equal(calls[1][2],'next-token');a.context.receiveSearchResults(2,[one,two],'',{next:'third'});
 assert.equal(a.document.getElementById('list').children.length,2);
 a.document.getElementById('moreResults').onclick();a.context.receiveSearchResults(3,[],'No connection');
 assert.equal(a.document.getElementById('list').children.length,2);assert.equal(a.document.getElementById('moreResults').disabled,false);
});
test('Download button passes only the selected video ID and title',()=>{
 const a=app(),calls=[];a.context.MusicFlowAndroid.downloadTrack=(...args)=>calls.push(args);
 a.document.getElementById('query').value='Music';a.document.getElementById('searchForm').requestSubmit();
 a.context.receiveSearchResults(1,[{id:'abcdefghijk',title:'Song'}],'');
 a.document.getElementById('list').children[0].children[2].children[2].onclick();
 assert.deepEqual(calls,[['abcdefghijk','Song']]);
});
