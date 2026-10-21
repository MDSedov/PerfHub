// Проверки Vanilla JavaScript на встроенных модулях Node; зависимости/сборщик не добавляются.
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
process.chdir(require('path').resolve(__dirname,'../../..'));
function extract(file,name){
 const source=fs.readFileSync(file,'utf8');
 const pattern=new RegExp('^    (?:async )?function '+name+'\\(', 'm');
 const match=source.match(pattern);if(!match)throw Error(name);
 const start=match.index,next=source.indexOf('\n    }',start);
 return source.slice(start,next+6);
}
const settings='src/main/resources/templates/settings.html';
const functions=['normalizeText','normalizeTemplateForExport','normalizeTemplatePayload','inferTemplateType','inferTemplateTestType','normalizeConfluencePageId'];
const ctx=vm.createContext({toArray:v=>Array.isArray(v)?v:[],TEMPLATE_SUFFIX:'_template',RELEASE_TEMPLATE_SHORT_NAME:'release_template',TEMPLATE_TEST_TYPE_ALIASES:{confirm:'stability'},knownTestTypes:new Set(['max']),});
vm.runInContext(functions.map(name=>extract(settings,name)).join('\n'),ctx);
const fixture={id:99,shortName:'max_template',reportTitle:'DropApp',reportTitleShort:'DropApp',templateType:'TEST',testType:'max',charts:[{title:'CPU',dashboardUid:'demo',panelId:106,templateChartId:1,renderScope:'DROPAPP_DEPLOYMENT',deployment:'profile'},{title:'CPU',dashboardUid:'demo',panelId:106,templateChartId:2,renderScope:'DROPAPP_DEPLOYMENT',deployment:'other'},{title:'RAM',dashboardUid:'demo',panelId:78,templateChartId:3,renderScope:'DROPAPP_DEPLOYMENT',deployment:'profile'}]};
const exported=ctx.normalizeTemplateForExport(fixture), imported=ctx.normalizeTemplatePayload(JSON.parse(JSON.stringify(exported)));
assert.deepEqual(JSON.parse(JSON.stringify(imported.charts)),fixture.charts.map(({templateChartId,...rest})=>rest));
assert(!JSON.stringify(exported).includes('templateChartId'));
if(process.argv[2] && process.argv[3]) {
 const actual=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
 const restored=ctx.normalizeTemplatePayload(ctx.normalizeTemplateForExport(actual));
 fs.writeFileSync(process.argv[3],JSON.stringify(restored));
}
const moveCtx=vm.createContext({activeTemplateDraft:{charts:fixture.charts.map(v=>({...v}))},renderTemplateChartsTable:()=>{}});
vm.runInContext(extract('src/main/resources/templates/settings-templates.html','moveTemplateChart'),moveCtx);
moveCtx.moveTemplateChart(2,0,'before');
assert.deepEqual(moveCtx.activeTemplateDraft.charts.map(c=>[c.panelId,c.deployment]),[[78,'profile'],[106,'profile'],[106,'other']]);
const normalCtx=vm.createContext({});vm.runInContext(extract('src/main/resources/templates/tests.html','normalizeGrafanaPanels'),normalCtx);
const normalized=normalCtx.normalizeGrafanaPanels({dashboards:[{panels:[{templateChartId:1,templateChartOrder:0},{templateChartId:3,templateChartOrder:2}]},{panels:[{templateChartId:2,templateChartOrder:1}]}]});
assert.deepEqual(Array.from(normalized.panels,p=>p.templateChartId),[1,2,3]);
console.log('PASS: export/import fields and order; service ids excluded; reorder retains deployment; mixed dashboard order restored');

// Поведение действующих функций страницы на синтетическом транспорте, без DOM/браузера.
(async()=>{
 const formFile='src/main/resources/templates/settings-templates.html';
 let savedBody;
 const formCtx=vm.createContext({
  activeTemplateDraft:JSON.parse(JSON.stringify(fixture)),testTypeOptions:[{code:'max'}],templatesCache:[],
  TEMPLATE_SUFFIX:'_template',RELEASE_TEMPLATE_SHORT_NAME:'release_template',
  templateTypeInput:{value:'TEST'},templateReportTitleShortInput:{value:'DropApp'},
  templateReportTitleInput:{value:'DropApp'},templatePageIdInput:{value:''},
  getTemplateEditorHtml:()=>'',renderTemplateChartsTable:()=>{},
  isSaving:false,isDeleting:false,updateTemplateModalActions:()=>{},
  hideTemplateDetailsModal:()=>{},loadTemplates:async()=>{},showToast:()=>{},
  setTemplateModalAlert:message=>{throw Error(message);},
  fetch:async(url,options)=>{
   assert.equal(url,'/api/settings/confluence-templates/99');assert.equal(options.method,'PUT');
   savedBody=JSON.parse(options.body);return {ok:true,json:async()=>savedBody};
  }
 });
 vm.runInContext(['normalizeTemplateType','normalizeTestType','systemShortNameForTemplateType',
  'normalizeTemplatePageIdValue','findActiveTemplateByShortName','isSystemShortNameTaken',
  'findFirstAvailableTestType','ensureTemplateSystemSelection','moveTemplateChart',
  'buildTemplatePayload','saveTemplate'].map(n=>extract(formFile,n)).join('\n'),formCtx);
 await formCtx.moveTemplateChart(2,0,'before');await formCtx.saveTemplate();
 assert.deepEqual(savedBody.charts.map(c=>[c.panelId,c.deployment]),[[78,'profile'],[106,'profile'],[106,'other']]);
 console.log('PASS: reorder -> actual saveTemplate -> HTTP payload retains row order and deployment');
 const range='src/main/resources/templates/grafana-panels.html';
 const rendered=[],errors=[],timeouts=[];
 let invocation=0;
 const rangeCtx=vm.createContext({
  AbortController,CHART_RENDER_TIMEOUT_MS:30000,
  window:{setTimeout:(fn,ms)=>{timeouts.push(ms);return 1;},clearTimeout:()=>{}},
  activeRenderRunId:0,renderInProgress:false,showPanelsButton:{},
  renderChartPlaceholders:template=>template.charts,
  showToast:()=>{},setExportLaunchState:()=>{},updateExportButtonState:()=>{},
  renderChartImage:(key,panel)=>rendered.push([key,panel.templateChartId]),
  renderChartPlaceholderError:(key,message)=>errors.push([key,message]),
  fetch:async(url,options)=>{
   assert.equal(url,'/api/grafana/panels/range/panel');
   const body=JSON.parse(options.body);invocation++;
   if(invocation===1)return {ok:false,text:async()=>JSON.stringify({message:'synthetic failure'})};
   return {ok:true,json:async()=>({templateChartId:body.templateChartId})};
  }
 });
 vm.runInContext(['extractErrorMessage','getChartKey','getChartTitle','renderChartByRange','renderByRange'].map(n=>extract(range,n)).join('\n'),rangeCtx);
 await rangeCtx.renderByRange('from','to',{id:1,charts:fixture.charts});
 assert.equal(errors.length,1);assert.equal(rendered.length,2);
 assert.deepEqual(rendered.map(item=>item[1]),[2,3]);assert(timeouts.every(ms=>ms===30000));
 rangeCtx.fetch=async()=>({ok:true,json:async()=>({templateChartId:999})});
 await assert.rejects(rangeCtx.renderChartByRange('from','to',1,fixture.charts[0]),/другой строки/);
 let resolve;
 rangeCtx.fetch=()=>new Promise(done=>{resolve=done;});
 const previous=rendered.length;
 const pending=rangeCtx.renderByRange('from','to',{id:1,charts:fixture.charts});
 rangeCtx.activeRenderRunId++;
 resolve({ok:true,json:async()=>({templateChartId:1})});await pending;
 assert.equal(rendered.length,previous);
 const calls=[];
 const cacheCtx=vm.createContext({fetchGrafanaPanels:(id,options)=>{calls.push([id,options]);return Promise.resolve({});}});
 vm.runInContext(['ensureGrafanaPanels','tryLoadGrafanaPanelsFromServerCache'].map(n=>extract('src/main/resources/templates/tests.html',n)).join('\n'),cacheCtx);
 await cacheCtx.tryLoadGrafanaPanelsFromServerCache('test');await cacheCtx.tryLoadGrafanaPanelsFromServerCache('test');
 assert.equal(calls.length,2);assert(calls.every(call=>call[1].cacheOnly===true));
 console.log('PASS: per-row identity; partial failure continues; 30s timeout; wrong-row and late results rejected; server cache revalidated on reopening');
})().catch(error=>{console.error(error);process.exitCode=1;});
