import {pipeline,env} from './nmt-runtime/transformers.min.js';
env.allowRemoteModels=false;env.allowLocalModels=true;env.useBrowserCache=false;env.localModelPath=new URL('./nmt/',self.location.href).href;
env.backends.onnx.wasm.wasmPaths=new URL('./nmt-runtime/',self.location.href).href;env.backends.onnx.wasm.numThreads=1;env.backends.onnx.wasm.proxy=false;
self.onmessage=async event=>{
 const job=event.data;let output='',completed=0,engine;
 try{
  if(!['ru-en','en-ru','ru-uk','uk-ru'].includes(job.from+'-'+job.to))throw Error('Неподдерживаемая пара языков.');
  self.postMessage({id:job.id,status:'running',message:'Подготавливаю встроенную переводную модель…',output,completed,total:job.chunks.length});
  engine=await pipeline('translation',job.from+'-'+job.to,{quantized:true,local_files_only:true});
  for(const part of job.chunks){
   let a=0,b=part.length;while(a<b&&/\s/u.test(part[a]))a++;while(b>a&&/\s/u.test(part[b-1]))b--;
   let text='';if(a!==b){
    const tokens=engine.tokenizer(part.slice(a,b),{padding:false,truncation:false});
    if(tokens.input_ids.dims[1]>510)throw Error('Слишком сложный фрагмент. Выдели меньшую часть текста.');
    const ids=await engine.model.generate(tokens.input_ids,{max_new_tokens:512,num_beams:4,do_sample:false,forced_eos_token_id:null});
    const eos=engine.model.config.eos_token_id,sequence=ids[0];if(sequence.length>=512||!sequence.length||!([eos].flat().includes(Number(sequence[sequence.length-1]))))throw Error('Фрагмент не завершён. Выбери меньшую часть текста.');
    text=engine.tokenizer.batch_decode(ids,{skip_special_tokens:true})[0].trim();if(!text)throw Error('Модель вернула пустой перевод.');
   }
   output+=part.slice(0,a)+text+part.slice(b);if(output.length>300000)throw Error('Результат превышает 300 000 символов.');completed++;
   self.postMessage({id:job.id,status:'running',output,completed,total:job.chunks.length,message:`Переведено фрагментов: ${completed} из ${job.chunks.length}`});
  }
  self.postMessage({id:job.id,status:'done',output,completed,total:job.chunks.length,message:'Перевод завершён. Проверь результат; его можно исправить и сохранить в TXT.'});
 }catch(error){self.postMessage({id:job.id,status:'error',output,completed,total:job.chunks?.length||0,message:'Перевод не завершён: '+error.message});}
 finally{if(engine)await engine.dispose();}
};
