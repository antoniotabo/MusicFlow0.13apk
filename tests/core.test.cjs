const test=require('node:test'),assert=require('node:assert/strict');
const c=require('../app/src/main/assets/core.js');
test('Accept supported YouTube URLs and reject deceptive hosts',()=>{
 for(const url of ['https://youtu.be/jfKfPfyJRdk','https://www.youtube.com/watch?v=jfKfPfyJRdk&x=1','https://youtube.com/shorts/jfKfPfyJRdk','jfKfPfyJRdk'])assert.equal(c.videoId(url),'jfKfPfyJRdk');
 for(const url of ['https://youtube.com.evil.test/watch?v=jfKfPfyJRdk','javascript:alert(1)','https://evil.test/jfKfPfyJRdk','abc'])assert.equal(c.videoId(url),null);
});
test('Imported library validates tracks, deduplicates and excludes credentials',()=>{
 const track={id:'jfKfPfyJRdk',title:'Music',channel:'Test'};
 const lib=c.library({favorites:[track,track,{id:'bad',title:'bad'}],playlists:[{name:'Focus',tracks:[track]}],apiKey:'secret'});
 assert.equal(lib.favorites.length,1);assert.equal(lib.playlists[0].tracks.length,1);assert.equal(lib.apiKey,undefined);
 assert.throws(()=>c.library({favorites:[]}));
});
test('Audio favorites retain their type without exporting private document URIs',()=>{
 const input={id:'audio-00000000-0000-4000-8000-000000000001',kind:'audio',title:'Local song',channel:'Device',uri:'content://private/document'};
 const out=c.library({favorites:[input],playlists:[{name:'My music',tracks:[input]}]});
 assert.equal(out.favorites[0].kind,'audio');assert.equal(out.favorites[0].uri,undefined);assert.equal(out.playlists[0].tracks[0].id,input.id);assert.equal(c.track({...input,id:'https://example.com'}),null);
});

test('History validates corrupted entries and keeps at most twenty unique queries',()=>{
 assert.equal(c.history(['  Salsa  ','salsa',null,{},...Array.from({length:30},(_,i)=>'Song '+i)]).length,20);
 assert.deepEqual(c.history(['  Salsa  ','salsa',null,{},'Rock']),['Salsa','Rock']);
});

