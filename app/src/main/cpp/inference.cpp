#ifndef USTAVY_HOST_TEST
#include <jni.h>
#else
#include <fstream>
#include <iostream>
#include <iterator>
#endif
#include "llama.h"
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <algorithm>
static std::atomic<bool> stop{false};
static bool abort_eval(void*) {return stop.load();}
static std::string generate_impl(const std::string& filename,const std::string& prompt,int limit,bool strict){
 std::string out;
  static bool initialized=[](){llama_backend_init();return true;}();(void)initialized;
  auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.progress_callback=[](float,void*){return !stop.load();};
  std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(filename.c_str(),mp),llama_model_free);
  if(!model)throw std::runtime_error(stop?"Остановлено.":"Не удалось загрузить модель. Проверь свободную память.");
  const auto* vocab=llama_model_get_vocab(model.get());
  int n=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
  if(n<=0||n>3500||(strict&&n+limit>4096))throw std::runtime_error("Слишком длинный текст. Выбери меньший фрагмент.");
  std::vector<llama_token> tokens(n);
  if(llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),n,true,true)<0)throw std::runtime_error("Ошибка обработки текста.");
  auto cp=llama_context_default_params();cp.n_ctx=4096;cp.n_batch=256;cp.n_ubatch=256;cp.n_threads=4;cp.n_threads_batch=4;cp.abort_callback=abort_eval;
  std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model.get(),cp),llama_free);
  if(!ctx)throw std::runtime_error("Недостаточно памяти для ИИ.");
  std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()),llama_batch_ext_free);
  if(!batch)throw std::runtime_error("Не удалось подготовить вычисления.");
  auto eval=[&](const llama_token* t,int count,int start){
   if(stop)throw std::runtime_error("Остановлено.");
   llama_batch_ext_clear(batch.get());
   for(int i=0;i<count;i++){int idx=llama_batch_ext_add_token(batch.get(),0,t[i]);llama_pos p=start+i;llama_batch_ext_set_pos(batch.get(),idx,&p);}
   llama_batch_ext_set_output_logits(batch.get(),count-1,true);
   if(llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get()))throw std::runtime_error(stop?"Остановлено.":"Ошибка вычисления модели.");
  };
  for(int start=0;start<n;start+=256)eval(tokens.data()+start,std::min(256,n-start),start);
  std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
  bool ended=false;for(int i=0;i<limit&&!stop;i++){
   llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);if(llama_vocab_is_eog(vocab,token)){ended=true;break;}
   char piece[256];int len=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,false);
   if(len<0){std::vector<char> big(-len);len=llama_token_to_piece(vocab,token,big.data(),big.size(),0,false);if(len>0)out.append(big.data(),len);}else out.append(piece,len);
   eval(&token,1,n+i);
  }
  if(strict){if(stop)throw std::runtime_error("Остановлено.");if(!ended)throw std::runtime_error("Фрагмент перевода не завершён: достигнут лимит вывода.");if(out.empty())throw std::runtime_error("Модель не сформировала перевод.");}
  else if(stop)out+="\n[Остановлено]";else if(out.empty())out="Модель не сформировала ответ.";
  return out;
}
#ifndef USTAVY_HOST_TEST
static std::string bytes(JNIEnv* e,jbyteArray a){int n=e->GetArrayLength(a);std::string s(n,'\0');e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(s.data()));return s;}
static jbyteArray result(JNIEnv* e,const std::string& text){jbyteArray out=e->NewByteArray(text.size());if(out)e->SetByteArrayRegion(out,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));return out;}
extern "C" JNIEXPORT void JNICALL Java_ru_ustavy_offline_LocalAi_cancel(JNIEnv*,jclass){stop=true;}
extern "C" JNIEXPORT void JNICALL Java_ru_ustavy_offline_LocalAi_reset(JNIEnv*,jclass){stop=false;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_ru_ustavy_offline_LocalAi_generate(JNIEnv* e,jclass,jbyteArray path,jbyteArray input){
 try{return result(e,generate_impl(bytes(e,path),bytes(e,input),384,false));}catch(const std::exception& ex){return result(e,std::string("Ошибка: ")+ex.what());}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_ru_ustavy_offline_LocalAi_translate(JNIEnv* e,jclass,jbyteArray path,jbyteArray input){
 try{return result(e,generate_impl(bytes(e,path),bytes(e,input),1024,true));}catch(const std::exception& ex){e->ThrowNew(e->FindClass("java/io/IOException"),ex.what());return nullptr;}
}
#else
int main(int argc,char** argv){if(argc!=3)return 2;std::ifstream file(argv[2],std::ios::binary);std::string prompt((std::istreambuf_iterator<char>(file)),std::istreambuf_iterator<char>());try{std::cout<<generate_impl(argv[1],prompt,1024,true);return 0;}catch(const std::exception& ex){std::cerr<<ex.what();return 1;}}
#endif

