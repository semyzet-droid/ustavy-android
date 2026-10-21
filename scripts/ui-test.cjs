const { chromium }=require("playwright");
const assert=require("node:assert/strict");
const {pathToFileURL}=require("node:url");
const {resolve}=require("node:path");
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
 const context=await browser.newContext({viewport:{width:412,height:915},offline:true});
 const page=await context.newPage();const errors=[];page.on("pageerror",e=>errors.push(e.message));
 await page.addInitScript(()=>{window.Phone={loadCorpus(){window.dispatchEvent(new CustomEvent("corpus",{detail:{documents:[{id:"fixture",name:"Тестовый документ — не устав",text:"Введение\n1. Учёт личного состава\n12. Обязанности для теста\n12.1. Подпункт для теста\n120. Четвёртый пункт",source:"Синтетические тестовые данные",note:"Только для проверки приложения"}]}}))},loadBooks(){window.dispatchEvent(new CustomEvent("books",{detail:{books:[{id:"pdf-fixture",name:"Иллюстрированная книга.pdf",kind:"pdf",pages:12,position:4}]}}))},openBook(id){window.openedBook=id},readTextBook(id){window.readTextId=id},importBook(){},importText(){},cancelAi(){}}});
 await page.goto(pathToFileURL(resolve("app/src/main/assets/index.html")).href);
 await page.locator("#query").fill("ст. 12");await page.waitForTimeout(250);
 assert.equal(await page.locator(".result").count(),1);
 assert.match(await page.locator(".result").innerText(),/Обязанности/);
 await page.locator("#query").fill("учет СОСТАВА");await page.waitForTimeout(250);assert.equal(await page.locator(".result").count(),1);
 await page.locator(".result").click();await page.locator("#bookmark").click();
 await page.locator("[data-tab=saved]").click();assert.equal(await page.locator("#bookmarks .result").count(),1);
 await page.reload();await page.locator("[data-tab=saved]").click();assert.equal(await page.locator("#bookmarks .result").count(),1);
 await page.locator("[data-tab=learn]").click();assert.equal(await page.locator("#quiz button").count(),4);
 const result=await page.evaluate(()=>{
  const t=window.UstavyTest,p=t.parse("Введение\n1. Начало\nпродолжение\n12.1. Текст\nСтатья 13. Финал");
  return {parts:p.length,decimal:p[2].number,exact:t.matches({number:"120",text:"120. Текст"},"12"),yo:t.matches({number:"1",text:"УЧЁТ состава"},"учет")};
 });
 assert.deepEqual(result,{parts:4,decimal:"12.1",exact:false,yo:true});assert.deepEqual(errors,[]);
 await page.locator("[data-tab=books]").click();
 assert.match(await page.locator("#bookList").innerText(),/продолжить со стр. 5/);
 await page.locator("#bookList button").click();assert.equal(await page.evaluate(()=>window.openedBook),"pdf-fixture");
 await page.evaluate(()=>window.dispatchEvent(new CustomEvent("bookindexed",{detail:{id:"book-pdf-fixture",bookId:"pdf-fixture",name:"Иллюстрированная книга.pdf",text:"7. PDF search needle"}})));
 await page.locator("[data-tab=search]").click();await page.locator("#query").fill("PDF needle");await page.waitForTimeout(250);
 assert.equal(await page.locator("#results .result").count(),1);await page.locator("#results .result").click();
 await page.locator("#readWhole").click();assert.equal(await page.evaluate(()=>window.openedBook),"pdf-fixture");
 // Themes are readable, persistent, and independent of imported study data.
 await page.locator("#appearanceButton").click();await page.locator('[data-scheme="light"]').click();
 assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue("--fg").trim()),"#20291f");
 await page.locator("#closeAppearance").click();await page.reload();
 assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue("--bg").trim()),"#f7f8f4");
 await page.locator("#appearanceButton").click();
 await page.locator("#backgroundColor").fill("#faf0cc");await page.locator("#backgroundColor").dispatchEvent("input");
 assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue("--bg").trim()),"#faf0cc");
 await page.locator("#closeAppearance").click();await page.reload();
 assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue("--bg").trim()),"#faf0cc");
 await page.evaluate(()=>window.dispatchEvent(new CustomEvent("appearance",{detail:{image:"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jp1sAAAAASUVORK5CYII=",strength:40}})));
 assert.match(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),/^url\(/);
 await page.locator("#appearanceButton").click();await page.locator("#removeWallpaper").click();
 assert.equal(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),"none");
 await page.locator("#resetAppearance").click();assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue("--bg").trim()),"#101914");
 await page.locator("#closeAppearance").click();await page.locator("[data-tab=saved]").click();assert.equal(await page.locator("#bookmarks .result").count(),1);
 assert.deepEqual(errors,[]);
 await page.locator('[data-tab="search"]').click();await page.locator("#query").fill("учет");await page.waitForTimeout(250);await page.locator("#results .result").click();
 await page.evaluate(()=>{const r=document.createRange();r.selectNodeContents(document.getElementById("detailBody"));const s=window.getSelection();s.removeAllRanges();s.addRange(r);document.dispatchEvent(new Event("selectionchange"));});
 await page.locator("#saveSelection").click();await page.locator("#noteComment").fill("Запомнить обязанности");await page.locator("#saveNote").click();
 await page.locator('[data-tab="notes"]').click();assert.match(await page.locator("#notesList").innerText(),/Запомнить обязанности/);
 await page.reload();await page.locator('[data-tab="notes"]').click();assert.match(await page.locator("#notesList").innerText(),/Учёт личного состава/);
 await page.locator("#notesList button").filter({hasText:"К источнику"}).click();assert.equal(await page.locator(".annotation").count(),1);
 await page.locator(".annotation").click();await page.locator("#noteComment").fill("Исправленная заметка");await page.locator("#saveNote").click();
 await page.locator('[data-tab="notes"]').click();assert.match(await page.locator("#notesList").innerText(),/Исправленная заметка/);
 page.once("dialog",dialog=>dialog.accept());await page.locator("#notesList button").filter({hasText:"Удалить"}).click();assert.match(await page.locator("#notesList").innerText(),/Заметок пока нет/);
 assert.deepEqual(errors,[]);
 // UI transport is mocked; actual language generation is tested against the bundled model in CI.
 await page.evaluate(()=>{window.Phone.translate=json=>{window.translationRequest=JSON.parse(json);const d=window.translationRequest;window.dispatchEvent(new CustomEvent("translation",{detail:{...d,output:d.to==="uk"?"Дякую за допомогу.":"Thank you for your help.",status:"done",message:"Перевод завершён"}}));};window.Phone.saveTranslation=(text,name)=>{window.savedTranslation={text,name}};});
 await page.locator('[data-tab="translate"]').click();await page.locator("#translateInput").fill("Спасибо за помощь.");await page.locator("#translateTo").selectOption("uk");await page.locator("#startTranslation").click();
 assert.equal(await page.locator("#translateOutput").inputValue(),"Дякую за допомогу.");assert.equal(await page.evaluate(()=>window.translationRequest.to),"uk");
 await page.locator("#translateOutput").fill("Дуже дякую за допомогу.");await page.locator("#saveTranslationFile").click();assert.equal(await page.evaluate(()=>window.savedTranslation.text),"Дуже дякую за допомогу.");
 await page.reload();await page.locator('[data-tab="translate"]').click();assert.equal(await page.locator("#translateOutput").inputValue(),"Дуже дякую за допомогу.");
 await page.locator("#swapLanguages").click();assert.equal(await page.locator("#translateFrom").inputValue(),"uk");assert.equal(await page.locator("#translateTo").inputValue(),"ru");assert.equal(await page.locator("#translateInput").inputValue(),"Дуже дякую за допомогу.");assert.equal(await page.locator("#translateOutput").inputValue(),"");
 await page.locator("#translateFrom").selectOption("en");assert.deepEqual(await page.locator("#translateTo option").evaluateAll(options=>options.map(o=>o.value)),["ru"]);
 await page.evaluate(()=>window.dispatchEvent(new CustomEvent("translation",{detail:{status:"cancelled",output:"Готовая часть",message:"Остановлено. Результат неполный."}})));
 assert.equal(await page.locator("#saveTranslationFile").innerText(),"Сохранить неполный TXT");assert.match(await page.locator("#translationStatus").innerText(),/неполный/);
 assert.deepEqual(errors,[]);
 console.log("PASS: offline search, exact article number, Russian normalization, bookmarks persistence, quiz, paragraph splitting, book catalog, PDF text search reader bridge, theme persistence, custom color, wallpaper, reset, selected quote notes, editing, source navigation deletion, translator directions, draft persistence, edited TXT export and partial result labels.");
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});

