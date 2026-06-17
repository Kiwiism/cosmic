"use client";
import {Activity,BookOpen,ChevronRight,Clock3,Database,FileCode2,Gauge,History,Network,RotateCcw,Server,Settings2,ShieldCheck,SlidersHorizontal,Wrench,X} from "lucide-react";
import {useEffect,useState} from "react";
import {CmsAuth,CmsConnectionError} from "../components/auth/CmsAuth";
import {CmsNavItem,CmsShell} from "../components/layout/CmsShell";
import {PageToolbar} from "../components/ui/PageToolbar";
import {api} from "../lib/api";

type View="overview"|"settings"|"worlds"|"commands"|"agents"|"security"|"performance"|"maintenance"|"diagnostics"|"deployments"|"audit";
type Setting=Record<string,any>;
type WorldSummary={id:number;name:string;active:boolean};
const nav:readonly CmsNavItem<View>[]=[
 {key:"overview",label:"Overview",icon:Activity},{key:"settings",label:"Features & general",icon:Settings2},{key:"worlds",label:"Worlds & rates",icon:Gauge},
 {key:"commands",label:"Commands",icon:SlidersHorizontal},{key:"agents",label:"Agents",icon:Network},{key:"security",label:"Access & security",icon:ShieldCheck},
 {key:"performance",label:"Runtime & schedulers",icon:Clock3},{key:"maintenance",label:"Maintenance",icon:Wrench},
 {key:"diagnostics",label:"Diagnostics & logs",icon:Network},{key:"deployments",label:"Deployments",icon:Database},{key:"audit",label:"Audit & rollback",icon:History}
];
const compatibility:Record<string,{label:string,className:string}>={
 SERVER_ONLY:{label:"No client/WZ edit",className:"ok"},
 WZ_REQUIRED:{label:"WZ edit required",className:"wz"},
 CLIENT_REQUIRED:{label:"Client edit required",className:"client"},
 WZ_AND_CLIENT:{label:"WZ + client edit",className:"both"}
};

export default function App(){
 const [auth,setAuth]=useState<boolean|null>(null),[setup,setSetup]=useState(false),[startupError,setStartupError]=useState(""),[view,setView]=useState<View>("overview"),[drawer,setDrawer]=useState<Setting|null>(null),[settingsVersion,setSettingsVersion]=useState(0);
 useEffect(()=>{api("/api/auth/me").then(()=>setAuth(true)).catch(async()=>{
  try{const status=await api<{required:boolean}>("/api/setup/status");setSetup(status.required);setAuth(false)}
  catch{setStartupError("The Server CMS API is offline or its database credentials are not configured.");setAuth(false)}
 })},[]);
 if(auth===null)return <div className="splash">Preparing Cosmic Server CMS...</div>;
 if(startupError)return <CmsConnectionError mark="SC" productName="Server CMS" message={startupError}/>;
 if(!auth)return <CmsAuth mark="SC" productName="Server CMS" secureLabel="SECURE OPERATIONS" setup={setup} onReady={()=>setAuth(true)}/>;
 return <CmsShell activeView={view} brandMark="SC" brandSubtitle="Server CMS" eyebrow="COSMIC CONTROL CENTER"
  headerStatus="Server configuration only" inspectorOpen={Boolean(drawer)} navigation={nav}
  onNavigate={next=>{setView(next);setDrawer(null)}} sidebarStatus={<><Server size={16}/> Independent operations console</> }
  inspector={drawer&&<SettingDock setting={drawer} close={()=>setDrawer(null)} changed={next=>{setDrawer(next);setSettingsVersion(value=>value+1)}}/>}>
   {view==="overview"&&<Overview refreshKey={settingsVersion} open={s=>{setDrawer(s);setView("settings")}}/>}
   {view==="settings"&&<Configuration refreshKey={settingsVersion} onOpen={setDrawer}/>}
   {view==="worlds"&&<WorldsAndRates refreshKey={settingsVersion} onOpen={setDrawer}/>}
   {view==="commands"&&<Commands onOpen={setDrawer}/>}
   {view==="agents"&&<Agents/>}
   {view!=="overview"&&view!=="settings"&&view!=="worlds"&&view!=="commands"&&view!=="agents"&&view!=="audit"&&<CategoryPage view={view} refreshKey={settingsVersion} onOpen={setDrawer}/>}
   {view==="audit"&&<Audit refreshKey={settingsVersion}/>}
  </CmsShell>
}

function Overview({refreshKey,open}:{refreshKey:number;open:(s:Setting)=>void}){
 const [data,setData]=useState<any>();const [recent,setRecent]=useState<Setting[]>([]);
 useEffect(()=>{api("/api/dashboard").then(setData);api<Setting[]>("/api/settings").then(x=>setRecent(x.filter(s=>s.override_value).slice(0,6)))},[refreshKey]);
 if(!data)return <Loading/>;
 const server=data.server||{};
 return <><div className={`server-banner ${server.status==="UP"?"up":"down"}`}><div className="pulse"/><div><strong>Cosmic server {String(server.status).toLowerCase()}</strong>
  <span>{server.status==="UP"?`${server.worlds} worlds · ${server.channels} channels · ${server.onlinePlayers} online`:"Overrides remain stored; Cosmic will fall back safely at startup"}</span></div></div>
  <div className="metrics">{[["Managed settings",data.settings,Settings2],["YAML origins",data.yamlSettings,BookOpen],["Hardcoded origins",data.hardcodedSettings,FileCode2],["New settings",data.newSettings,SlidersHorizontal],["Active overrides",data.overrides,Gauge],["Restart settings",data.restartSettings,RotateCcw]].map(([l,v,I]:any)=><article key={l}><I size={19}/><small>{l}</small><strong>{Number(v||0).toLocaleString()}</strong></article>)}</div>
  <div className="summary-grid"><article className="panel"><Title title="Configuration readiness" sub="Desired state, runtime state and fallback remain distinct"/>
   <Status label="Server bridge" value={server.status||"OFFLINE"}/><Status label="Override database" value="Connected"/><Status label="Fallback chain" value="CMS override -> config.yaml -> Java default"/>
   <Status label="Client/WZ safeguards" value="Compatibility classified"/></article>
   <article className="panel dark"><p className="eyebrow">PROVENANCE FIRST</p><h2>Every setting remembers where it came from.</h2><p>Open any entry to see its original YAML key or Java source, implementation consumers, fallback, apply mode, and whether native gameplay display needs a client or WZ update.</p></article></div>
  <article className="panel"><Title title="Recent active overrides" sub="Only values diverging from original Cosmic behavior"/>{recent.length?recent.map(s=><button className="setting-row" onClick={()=>open(s)} key={s.setting_key}><div><strong>{s.display_name}</strong><code>{s.setting_key}</code></div><span>{s.override_value}</span><ChevronRight size={16}/></button>):<p className="muted">No overrides. Cosmic is using its original configuration.</p>}</article></>
}

function Configuration({refreshKey,onOpen}:{refreshKey:number;onOpen:(s:Setting)=>void}){
 const [rows,setRows]=useState<Setting[]>([]),[cats,setCats]=useState<string[]>([]),[q,setQ]=useState(""),[cat,setCat]=useState(""),[origin,setOrigin]=useState(""),[compat,setCompat]=useState("");
 const ownedCategories=new Set(["Worlds & Channels","Worlds & rates","Commands","Authentication & Sessions","Security & Anti-Abuse","Runtime & Performance","Schedulers","Maintenance","Diagnostics","Diagnostics & Logs","Network"]);
 useEffect(()=>{api<string[]>("/api/settings/categories").then(values=>setCats(values.filter(value=>!ownedCategories.has(value))))},[]);
 useEffect(()=>{const t=setTimeout(()=>{const p=new URLSearchParams({q,category:cat,origin,compatibility:compat,scopeType:"GLOBAL"});api<Setting[]>(`/api/settings?${p}`).then(values=>setRows(values.filter(value=>!ownedCategories.has(value.category))))},120);return()=>clearTimeout(t)},[q,cat,origin,compat,refreshKey]);
 return <><article className="panel intro"><Title title="Features & general" sub="Only global settings without a dedicated operations page are shown here."/></article><PageToolbar query={q} onQueryChange={setQ} placeholder="Search general setting, key or description">
  <select value={cat} onChange={e=>setCat(e.target.value)}><option value="">All sections</option>{cats.map(x=><option key={x}>{x}</option>)}</select>
  <select value={origin} onChange={e=>setOrigin(e.target.value)}><option value="">All origins</option><option>YAML_EXISTING</option><option>JAVA_HARDCODED</option><option>SERVER_CMS_NEW</option></select>
  <select value={compat} onChange={e=>setCompat(e.target.value)}><option value="">All compatibility</option>{Object.keys(compatibility).map(x=><option key={x}>{x}</option>)}</select></PageToolbar>
  <p className="count">{rows.length} settings</p><div className="settings-grid">{rows.map(s=><SettingCard key={s.setting_key} setting={s} open={()=>onOpen(s)}/>)}</div></>
}

function WorldsAndRates({refreshKey,onOpen}:{refreshKey:number;onOpen:(s:Setting)=>void}){
 const [worlds,setWorlds]=useState<WorldSummary[]>([]),[selected,setSelected]=useState(0),[rows,setRows]=useState<Setting[]>([]),[globalRows,setGlobalRows]=useState<Setting[]>([]),[q,setQ]=useState("");
 useEffect(()=>{api<WorldSummary[]>("/api/worlds").then(setWorlds);api<Setting[]>("/api/settings?category=Worlds%20%26%20Channels&scopeType=GLOBAL").then(setGlobalRows)},[refreshKey]);
 useEffect(()=>{const timer=setTimeout(()=>{const params=new URLSearchParams({q,scopeType:"WORLD",scopeId:String(selected)});api<Setting[]>(`/api/settings?${params}`).then(setRows)},120);return()=>clearTimeout(timer)},[selected,q,refreshKey]);
 const world=worlds.find(value=>value.id===selected);
 const rates=rows.filter(setting=>setting.category==="Worlds & rates");
 const operations=rows.filter(setting=>setting.category==="Worlds & Channels");
 return <><article className="panel intro"><Title title="Choose a world" sub="Each world keeps independent rates and operational settings. Changes apply after a server restart."/></article>
  <div className="selector-grid world-selector">{worlds.map(value=><button type="button" className={`selector-card ${selected===value.id?"selected":""}`} key={value.id} onClick={()=>setSelected(value.id)}>
   <Gauge size={20}/><div><strong>{value.name}</strong><small>World {value.id}</small></div><span className={value.active?"active-state":"inactive-state"}>{value.active?"Active":"Configured"}</span></button>)}</div>
  <PageToolbar query={q} onQueryChange={setQ} placeholder={`Search ${world?.name||`World ${selected}`} settings`}/>
  <section className="page-section"><Title title="World topology" sub="Global world count and server-wide channel capacity"/>
   <div className="settings-grid">{globalRows.map(setting=><SettingCard key={setting.setting_key} setting={setting} open={()=>onOpen(setting)}/>)}</div></section>
  <section className="page-section"><Title title={`${world?.name||`World ${selected}`} rates`} sub={`EXP, meso, drops, quests, fishing and travel for World ${selected}`}/>
   <div className="settings-grid">{rates.map(setting=><SettingCard key={setting.setting_key} setting={setting} open={()=>onOpen(setting)}/>)}</div></section>
  <section className="page-section"><Title title="World operations" sub="Channels, login-list presentation and world messages"/>
   <div className="settings-grid">{operations.map(setting=><SettingCard key={setting.setting_key} setting={setting} open={()=>onOpen(setting)}/>)}</div></section>
  {!rows.length&&<article className="panel"><p className="muted">No world settings match this search.</p></article>}</>
}

function CategoryPage({view,refreshKey,onOpen}:{view:View;refreshKey:number;onOpen:(s:Setting)=>void}){
 const map:Record<string,string[]>={worlds:["Worlds & Channels","Worlds & rates"],commands:["Commands"],security:["Authentication & Sessions","Security & Anti-Abuse"],performance:["Runtime & Performance","Schedulers"],maintenance:["Maintenance"],diagnostics:["Diagnostics & Logs"],deployments:["Network"]};
 const fullWidth=view==="diagnostics"||view==="deployments";
 const [rows,setRows]=useState<Setting[]>([]);useEffect(()=>{Promise.all((map[view]||[]).map(category=>api<Setting[]>(`/api/settings?category=${encodeURIComponent(category)}`))).then(x=>setRows(x.flat()))},[view,refreshKey]);
 return <><article className="panel intro"><Title title={nav.find(x=>x.key===view)?.label||view} sub="Settings are grouped here without losing their original source and compatibility metadata."/></article><div className={fullWidth?"full-width-list":"settings-grid"}>{rows.map(s=><SettingCard key={s.setting_key} setting={s} open={()=>onOpen(s)}/>)}</div>{!rows.length&&<article className="panel"><p className="muted">This operations module is scaffolded for the next managed controls. Use Configuration to browse the current catalog.</p></article>}</>
}

function Agents(){
 const [agents,setAgents]=useState<any[]>([]),[selected,setSelected]=useState<any|null>(null),[q,setQ]=useState(""),[charQ,setCharQ]=useState(""),[chars,setChars]=useState<any[]>([]),[plan,setPlan]=useState<any|null>(null),[logs,setLogs]=useState<any[]>([]),[memory,setMemory]=useState<any[]>([]),[goals,setGoals]=useState<any[]>([]),[policies,setPolicies]=useState<any[]>([]),[scripts,setScripts]=useState<any[]>([]),[editingScript,setEditingScript]=useState<any|null>(null),[runtimeResult,setRuntimeResult]=useState<any|null>(null),[goalDraft,setGoalDraft]=useState({goalType:"IDLE",priority:0,targetMap:"",targetRef:""}),[scriptDraft,setScriptDraft]=useState({name:"idle-town",version:1,enabled:true,scriptType:"TEXT",body:"IDLE 30"}),[reason,setReason]=useState("Updated through Server CMS"),[error,setError]=useState("");
 const load=()=>api<any[]>(`/api/agents?q=${encodeURIComponent(q)}`).then(rows=>{setAgents(rows);if(selected){const next=rows.find(row=>row.id===selected.id);if(next)setSelected(next)}}).catch(x=>setError((x as Error).message));
 const loadScripts=()=>api<any[]>("/api/agents/scripts").then(setScripts).catch(()=>setScripts([]));
 const loadAgentDetails=()=>{if(!selected){setPlan(null);setLogs([]);setMemory([]);setGoals([]);setPolicies([]);return}api(`/api/agents/${selected.id}/spawn-plan`).then(setPlan).catch(()=>setPlan(null));api<any[]>(`/api/agents/${selected.id}/logs`).then(setLogs).catch(()=>setLogs([]));api<any[]>(`/api/agents/${selected.id}/memory`).then(setMemory).catch(()=>setMemory([]));api<any[]>(`/api/agents/${selected.id}/goals`).then(setGoals).catch(()=>setGoals([]));api<any[]>(`/api/agents/${selected.id}/policies`).then(setPolicies).catch(()=>setPolicies([]))};
 useEffect(()=>{const timer=setTimeout(load,160);return()=>clearTimeout(timer)},[q]);
 useEffect(()=>{loadScripts()},[]);
 useEffect(()=>{setRuntimeResult(null);loadAgentDetails()},[selected?.id]);
 useEffect(()=>{const timer=setTimeout(()=>{if(charQ.trim().length<2){setChars([]);return}api<any[]>(`/api/agents/characters?q=${encodeURIComponent(charQ)}`).then(setChars).catch(()=>setChars([]))},160);return()=>clearTimeout(timer)},[charQ]);
 async function createAgent(characterId:number){setError("");try{const created=await api<any>("/api/agents",{method:"POST",body:JSON.stringify({characterId,enabled:false,defaultMode:"IDLE",behaviorProfile:"default",personalityProfile:"default",llmEnabled:false})});setSelected(created);setCharQ("");setChars([]);load()}catch(x){setError((x as Error).message)}}
 async function saveAgent(){if(!selected)return;setError("");try{const updated=await api<any>(`/api/agents/${selected.id}`,{method:"PUT",body:JSON.stringify({enabled:Boolean(selected.enabled),displayName:selected.display_name,defaultMode:selected.default_mode,behaviorProfile:selected.behavior_profile,personalityProfile:selected.personality_profile,scriptName:selected.script_name,llmEnabled:Boolean(selected.llm_enabled),reason})});setSelected(updated);load()}catch(x){setError((x as Error).message)}}
 async function runtimeAction(action:string){if(!selected)return;setError("");try{const result=await api<any>(`/api/agents/${selected.id}/runtime/${action}`,{method:"POST"});setRuntimeResult(result);load();loadAgentDetails()}catch(x){setError((x as Error).message)}}
 async function setPolicy(policy:any,enabled:boolean){if(!selected)return;setError("");try{await api<any>(`/api/agents/${selected.id}/policies/${encodeURIComponent(policy.key)}`,{method:"PUT",body:JSON.stringify({enabled,reason})});loadAgentDetails()}catch(x){setError((x as Error).message)}}
 async function resetPolicy(policy:any){if(!selected)return;setError("");try{await api<any>(`/api/agents/${selected.id}/policies/${encodeURIComponent(policy.key)}?reason=${encodeURIComponent(reason)}`,{method:"DELETE"});loadAgentDetails()}catch(x){setError((x as Error).message)}}
 async function createGoal(){if(!selected)return;setError("");try{await api<any>(`/api/agents/${selected.id}/goals`,{method:"POST",body:JSON.stringify({goalType:goalDraft.goalType,priority:Number(goalDraft.priority)||0,status:"PENDING",targetMap:goalDraft.targetMap?Number(goalDraft.targetMap):null,targetRef:goalDraft.targetRef||null})});setGoalDraft({...goalDraft,targetMap:"",targetRef:""});loadAgentDetails()}catch(x){setError((x as Error).message)}}
 async function setGoalStatus(goal:any,status:string){if(!selected)return;setError("");try{await api<any>(`/api/agents/${selected.id}/goals/${goal.id}/status`,{method:"PUT",body:JSON.stringify({status,reason})});loadAgentDetails()}catch(x){setError((x as Error).message)}}
 async function saveScript(script:any){setError("");try{const saved=await api<any>(script.id?`/api/agents/scripts/${script.id}`:"/api/agents/scripts",{method:script.id?"PUT":"POST",body:JSON.stringify({name:script.name,version:Number(script.version)||1,enabled:Boolean(script.enabled),scriptType:script.script_type||script.scriptType||"TEXT",body:script.body||"",reason})});setEditingScript(null);setScriptDraft({...scriptDraft,name:"idle-town",body:"IDLE 30"});loadScripts();return saved}catch(x){setError((x as Error).message)}}
 const goalProgress=(goal:any)=>{try{return goal.progress_json?JSON.parse(goal.progress_json):null}catch{return null}};
 const parseDetails=(value:any)=>{try{return value?JSON.parse(String(value)):null}catch{return null}};
 const latestRouteMemory=memory.find(item=>item.event_type==="NAVIGATION_ROUTE");
 const latestRoute=parseDetails(latestRouteMemory?.details_json);
 const route=latestRoute?.route;
 return <><article className="panel intro"><Title title="Agent foundation" sub="Dormant agent profiles, control preflight, logs and future behavior wiring. Runtime remains disabled unless enabled in Features & general."/></article>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search agent, account, character or ID"/>
  {error&&<div className="error">{error}</div>}
  <div className="agent-layout"><section className="panel"><Title title="Agent roster" sub={`${agents.length} profiles. Disabled profiles never enter the runtime registry.`}/>
   <div className="agent-list">{agents.map(agent=><button className={`agent-card ${selected?.id===agent.id?"selected":""}`} key={agent.id} onClick={()=>setSelected(agent)}>
    <div><strong>{agent.display_name||agent.character_name}</strong><small>{agent.account_name} {"->"} {agent.character_name} · Lv. {agent.level} · World {agent.world}</small></div>
    <span className={agent.enabled?"active-state":"inactive-state"}>{agent.enabled?"Enabled":"Disabled"}</span></button>)}</div></section>
   <section className="panel"><Title title="Create from character" sub="Regular Cosmic characters can be marked as future agents without changing their account."/>
    <div className="search compact"><Network size={17}/><input value={charQ} onChange={e=>setCharQ(e.target.value)} placeholder="Search account, IGN or character ID"/></div>
    <div className="agent-list compact-list">{chars.map(character=><button className="agent-card" disabled={Boolean(character.agent_profile_id)} key={character.id} onClick={()=>createAgent(character.id)}>
     <div><strong>{character.account_name} {"->"} {character.character_name}</strong><small>Lv. {character.level} · Job {character.job} · World {character.world} · Map {character.map}</small></div>
     <span className={character.agent_profile_id?"inactive-state":"active-state"}>{character.agent_profile_id?"Already agent":"Create"}</span></button>)}</div></section></div>
  {selected&&<div className="agent-detail"><section className="panel"><Title title={`${selected.display_name||selected.character_name} profile`} sub="These settings are persisted in the game database agent tables and picked up by Cosmic after restart."/>
   <div className="value-grid agent-edit"><label><small>Enabled</small><select className="edit" value={String(Boolean(selected.enabled))} onChange={e=>setSelected({...selected,enabled:e.target.value==="true"})}><option value="false">false</option><option value="true">true</option></select></label>
    <label><small>Display name</small><input className="edit" value={selected.display_name||""} onChange={e=>setSelected({...selected,display_name:e.target.value})}/></label>
    <label><small>Default mode</small><input className="edit" value={selected.default_mode||"IDLE"} onChange={e=>setSelected({...selected,default_mode:e.target.value})}/></label>
    <label><small>Behavior profile</small><input className="edit" value={selected.behavior_profile||"default"} onChange={e=>setSelected({...selected,behavior_profile:e.target.value})}/></label>
    <label><small>Personality profile</small><input className="edit" value={selected.personality_profile||"default"} onChange={e=>setSelected({...selected,personality_profile:e.target.value})}/></label>
    <label><small>Script name</small><input className="edit" list="agent-script-names" value={selected.script_name||""} onChange={e=>setSelected({...selected,script_name:e.target.value})}/><datalist id="agent-script-names">{scripts.map(script=><option key={script.id} value={script.name}/>)}</datalist></label>
    <label><small>LLM enabled</small><select className="edit" value={String(Boolean(selected.llm_enabled))} onChange={e=>setSelected({...selected,llm_enabled:e.target.value==="true"})}><option value="false">false</option><option value="true">true</option></select></label></div>
   <textarea value={reason} onChange={e=>setReason(e.target.value)} /><div className="actions"><button className="primary" onClick={saveAgent}>Save profile</button></div></section>
   <section className="panel"><Title title="Control preflight" sub="Readiness check for the future control shell; this does not start an agent."/>
    {plan?<><div className="value-grid"><Tile label="Ready" value={String(plan.ready)}/><Tile label="World" value={plan.world}/><Tile label="Channel" value={plan.channel}/><Tile label="Map" value={plan.mapId}/><Tile label="Spawn point" value={plan.spawnPoint}/><Tile label="Message" value={plan.message}/></div>
     <div className="actions runtime-actions"><button className="secondary" onClick={()=>runtimeAction("prepare")}>Prepare</button><button className="secondary" onClick={()=>runtimeAction("enter")}>Enter world</button><button className="secondary" onClick={()=>runtimeAction("tick")}>Dry-run tick</button><button className="danger" onClick={()=>runtimeAction("release")}>Release</button></div>
     {runtimeResult&&<pre className="code-box">{JSON.stringify(runtimeResult,null,2)}</pre>}</>:<p className="muted">Select an agent to view preflight state.</p>}</section>
   <section className="panel full-span"><Title title="Goals" sub="Dry-run planner reads the highest priority active goal before falling back to scripts."/>
    <div className="filters compact"><select value={goalDraft.goalType} onChange={e=>setGoalDraft({...goalDraft,goalType:e.target.value})}><option>IDLE</option><option>WAIT</option><option>ROAM</option><option>MOVE_TO_MAP</option><option>GRIND_TO_LEVEL</option><option>LOOT</option><option>NPC</option><option>SHOP</option><option>SAY</option><option>USE_ITEM</option></select><input className="edit" type="number" value={goalDraft.priority} onChange={e=>setGoalDraft({...goalDraft,priority:Number(e.target.value)})} placeholder="Priority"/><input className="edit" value={goalDraft.targetMap} onChange={e=>setGoalDraft({...goalDraft,targetMap:e.target.value})} placeholder="Target map"/><input className="edit" value={goalDraft.targetRef} onChange={e=>setGoalDraft({...goalDraft,targetRef:e.target.value})} placeholder="Target ref"/><button className="primary" onClick={createGoal}>Add goal</button></div>
    {goals.length?goals.map(goal=>{const progress=goalProgress(goal);return <div className="audit-row" key={goal.id}><strong>{goal.goal_type}</strong><code>{goal.status} · priority {goal.priority}</code><span>{String(goal.updated_at)}</span><p>Target map {goal.target_map??"any"} · ref {goal.target_ref??"none"}</p>
     {progress&&<p>Last tick: {progress.lastIntent}{progress.lastArgument?` ${progress.lastArgument}`:""} · dispatch {progress.dispatchStatus} · goal {progress.goalStatus||goal.status} · {progress.goalReason||"no goal decision"} · map {progress.mapId} · mobs {progress.monsters} · drops {progress.drops}</p>}
     <div className="policy-actions"><button className="secondary" onClick={()=>setGoalStatus(goal,"ACTIVE")}>Activate</button><button className="secondary" onClick={()=>setGoalStatus(goal,"PAUSED")}>Pause</button><button className="secondary" onClick={()=>setGoalStatus(goal,"COMPLETED")}>Complete</button><button className="secondary" onClick={()=>setGoalStatus(goal,"FAILED")}>Fail</button><button className="danger" onClick={()=>setGoalStatus(goal,"CANCELLED")}>Cancel</button></div>
     {goal.progress_json&&<details><summary>Progress JSON</summary><pre>{goal.progress_json}</pre></details>}</div>}):<p className="muted">No goals yet. Without a goal the agent falls back to script/default idle planning.</p>}</section>
   <section className="panel full-span"><Title title="Script library" sub="Reusable dry-run behavior scripts. The runtime parses these into intents but only no-op timing intents execute today."/>
    <div className="filters compact"><input className="edit" value={scriptDraft.name} onChange={e=>setScriptDraft({...scriptDraft,name:e.target.value})} placeholder="Script name"/><input className="edit" type="number" value={scriptDraft.version} onChange={e=>setScriptDraft({...scriptDraft,version:Number(e.target.value)})} placeholder="Version"/><select value={String(scriptDraft.enabled)} onChange={e=>setScriptDraft({...scriptDraft,enabled:e.target.value==="true"})}><option value="true">enabled</option><option value="false">disabled</option></select><button className="primary" onClick={()=>saveScript(scriptDraft)}>Create script</button></div>
    <textarea className="script-editor" value={scriptDraft.body} onChange={e=>setScriptDraft({...scriptDraft,body:e.target.value})}/>
    {scripts.length?scripts.map(script=><div className="audit-row" key={script.id}><strong>{script.name}</strong><code>v{script.version} · {script.enabled?"enabled":"disabled"} · {script.script_type}</code><span>{String(script.updated_at)}</span>{editingScript?.id===script.id?<><textarea className="script-editor" value={editingScript.body||""} onChange={e=>setEditingScript({...editingScript,body:e.target.value})}/><div className="filters compact"><input className="edit" value={editingScript.name||""} onChange={e=>setEditingScript({...editingScript,name:e.target.value})}/><input className="edit" type="number" value={editingScript.version||1} onChange={e=>setEditingScript({...editingScript,version:Number(e.target.value)})}/><select value={String(Boolean(editingScript.enabled))} onChange={e=>setEditingScript({...editingScript,enabled:e.target.value==="true"})}><option value="true">enabled</option><option value="false">disabled</option></select><button className="secondary" onClick={()=>setEditingScript(null)}>Cancel</button><button className="primary" onClick={()=>saveScript(editingScript)}>Save script</button></div></>:<><p>{String(script.body||"").split(/\r?\n/).slice(0,3).join(" · ")}</p><div className="policy-actions"><button className="secondary" onClick={()=>setEditingScript(script)}>Edit</button><button className="secondary" onClick={()=>setSelected(selected?{...selected,script_name:script.name}:selected)}>Use on selected agent</button></div></>}</div>):<p className="muted">No reusable scripts yet. Agents can still use inline: scripts or default idle fallback.</p>}</section>
   <section className="panel full-span"><Title title="Capability policies" sub="Per-agent policy gates. Enabling a capability only passes policy; unimplemented runtime handlers still block gameplay actions."/>
    <div className="policy-grid">{policies.map(policy=><article className="policy-card" key={policy.key}>
     <div><strong>{policy.label}</strong><code>{policy.key}</code><p>{policy.description}</p><small>Default {String(policy.defaultEnabled)} · Global {policy.globalValue??"unset"} · Agent {policy.agentValue??"unset"}</small></div>
     <span className={policy.effective?"active-state":"inactive-state"}>{policy.effective?"Allowed":"Blocked"}</span>
     <div className="policy-actions"><button className="secondary" onClick={()=>setPolicy(policy,true)}>Allow</button><button className="secondary" onClick={()=>setPolicy(policy,false)}>Block</button><button className="secondary" disabled={!policy.overridden} onClick={()=>resetPolicy(policy)}>Use fallback</button></div>
    </article>)}</div></section>
   <section className="panel full-span"><Title title="Navigation route state" sub="Latest dry-run route preview recorded by the server-side navigation adapter."/>
    {route?<div className="route-panel">
     <div className="value-grid"><Tile label="State" value={route.routeState||"unknown"}/><Tile label="From map" value={route.fromMapId}/><Tile label="Target map" value={route.toMapId}/><Tile label="Steps" value={route.stepCount}/><Tile label="Dispatch" value={latestRoute.dispatchStatus}/><Tile label="Recorded" value={latestRouteMemory?.created_at}/></div>
     <p className="muted">{latestRoute.dispatchMessage||route.message}</p>
     {route.nextStep&&<article className="route-next"><strong>Next portal</strong><span>{route.nextStep.portalName} on map {route.nextStep.fromMapId} -&gt; {route.nextStep.toMapId}</span><small>x {route.nextStep.position?.x??0}, y {route.nextStep.position?.y??0} | {route.nextStep.open?"open":"closed"} | {route.nextStep.scripted?"scripted":"plain portal"}</small></article>}
     {Array.isArray(route.steps)&&route.steps.length?<ol className="route-steps">{route.steps.map((step:any,index:number)=><li key={`${step.fromMapId}-${step.portalId}-${index}`}><strong>{index+1}. {step.fromMapName||step.fromMapId}</strong><span>Portal {step.portalName} -&gt; map {step.toMapId}</span><small>x {step.position?.x??0}, y {step.position?.y??0} | type {step.portalType} | {step.open?"open":"closed"} | {step.scripted?"scripted":"plain"}</small></li>)}</ol>:<p className="muted">No route steps recorded. The agent may already be on the target map, or the route was unavailable.</p>}
     <details><summary>Raw route JSON</summary><pre>{JSON.stringify(latestRoute,null,2)}</pre></details>
    </div>:<p className="muted">No route state yet. Add or activate a MOVE_TO_MAP goal, allow navigation policy, enter the agent, then run a dry-run tick.</p>}</section>
   <section className="panel full-span"><Title title="Recent memory" sub="Compact observation checkpoints recorded during dry-run ticks."/>
    {memory.length?memory.map(item=><div className="audit-row" key={item.id}><strong>{item.event_type}</strong><code>Importance {item.importance}</code><span>{String(item.created_at)}</span><p>{item.summary}</p>{item.details_json&&<details><summary>Details</summary><pre>{item.details_json}</pre></details>}</div>):<p className="muted">No memory events yet. Run a dry-run tick after entering an agent.</p>}</section>
   <section className="panel full-span"><Title title="Recent action logs" sub="Lifecycle and future action records from agent_action_logs"/>
    {logs.length?logs.map(log=><div className="audit-row" key={log.id}><strong>{log.action_type}</strong><code>{log.status}</code><span>{String(log.created_at)}</span><p>{log.message}</p></div>):<p className="muted">No runtime logs yet.</p>}</section></div>}</>
}

function Commands({onOpen}:{onOpen:(s:Setting)=>void}){
 const [allRows,setAllRows]=useState<any[]>([]),[safeguards,setSafeguards]=useState<Setting[]>([]),[q,setQ]=useState(""),[level,setLevel]=useState<number|null>(null),[editing,setEditing]=useState<any|null>(null);
 const load=()=>api<any[]>(`/api/commands?q=${encodeURIComponent(q)}`).then(setAllRows);
 useEffect(()=>{const timer=setTimeout(load,120);return()=>clearTimeout(timer)},[q]);
 useEffect(()=>{api<Setting[]>("/api/settings?category=Commands&scopeType=GLOBAL").then(setSafeguards)},[]);
 const rows=level===null?allRows:allRows.filter(command=>Number(command.effectiveLevel)===level);
 const accessLevels=[
  {level:0,name:"Regular player",description:"Informational and self-fixing commands only. Cannot affect other players."},
  {level:1,name:"Privileged player",description:"Convenience commands such as stat reassignment, map travel, and enhanced game information."},
  {level:2,name:"Tester / sandbox",description:"Self-only testing tools for levels, jobs, HP/MP, skills, quests, inventory, healing, and ID searches."},
  {level:3,name:"GM",description:"Player support and moderation: warp, jail, disconnect, ban, events, reloads, notices, and other-player management."},
  {level:4,name:"Moderator",description:"Powerful content and world tools: create or enhance items, spawn bosses and monsters, manage persistent entities, and award currency."},
  {level:5,name:"Administrator",description:"Technical world administration: rates, diagnostic and packet logging, session/IP inspection, runtime settings, and global notices."},
  {level:6,name:"Owner",description:"Full server control: GM levels, save and shutdown, disconnect everyone, manage worlds/channels, and clear server-wide data."}
 ];
 const selectors:[number|null,string][]=[[null,"All levels"],...[0,1,2,3,4,5,6].map(value=>[value,`Level ${value}`] as [number,string])];
 return <><article className="panel access-guide"><Title title="Access level guide" sub={level===null?"Command authority increases from Level 0 through Level 6.":"This level's intended command scope."}/>
   <div className={level===null?"access-guide-grid":"access-guide-grid selected-only"}>{accessLevels.filter(item=>level===null||item.level===level).map(item=><div className="access-guide-item" key={item.level}>
    <span>Level {item.level}</span><div><strong>{item.name}</strong><p>{item.description}</p></div></div>)}</div></article>
  <div className="selector-grid level-selector">{selectors.map(([value,label])=><button type="button" className={`selector-card ${level===value?"selected":""}`} key={label} onClick={()=>setLevel(value)}>
   <SlidersHorizontal size={19}/><div><strong>{label}</strong><small>{value===null?`${allRows.length} commands`:accessLevels[value].name}</small>{value!==null&&<em>{allRows.filter(command=>Number(command.effectiveLevel)===value).length} commands</em>}</div></button>)}</div>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search command or purpose"/>
  <p className="count">{rows.length} commands shown. Changes apply after restart and update the in-game @commands list.</p>
  <div className="command-grid">{rows.map(command=><button className={`command-card ${command.enabled?"":"disabled"}`} key={command.name} onClick={()=>setEditing(command)}>
   <div><strong>@{command.name}</strong><p>{command.description||command.implementation}</p><code>{command.implementation}</code></div>
   <div><span>Level {command.effectiveLevel}</span>{command.overridden&&<em>Overridden</em>}{!command.enabled&&<em>Hidden</em>}</div></button>)}</div>
  <section className="page-section"><Title title="Command safeguards" sub="Global restrictions and minimum access levels used by command-adjacent systems"/>
   <div className="settings-grid">{safeguards.map(setting=><SettingCard key={setting.setting_key} setting={setting} open={()=>onOpen(setting)}/>)}</div></section>
   {editing&&<CommandDock command={editing} close={()=>setEditing(null)} changed={next=>{setEditing(next);load()}}/>}</>
}

function CommandDock({command,close,changed}:{command:any;close:()=>void;changed:(c:any)=>void}){
 const [enabled,setEnabled]=useState(Boolean(command.enabled)),[level,setLevel]=useState(Number(command.effectiveLevel)),[reason,setReason]=useState("Changed through Server CMS"),[error,setError]=useState("");
 async function save(){try{setError("");changed(await api(`/api/commands/${command.name}`,{method:"PUT",body:JSON.stringify({enabled,requiredLevel:level,reason})}))}catch(x){setError((x as Error).message)}}
 async function reset(){await api(`/api/commands/${command.name}?reason=${encodeURIComponent(reason)}`,{method:"DELETE"});changed({...command,enabled:true,effectiveLevel:command.originalLevel,overridden:false})}
 return <aside className="drawer"><button className="close" onClick={close}><X/></button><div className="drawer-head"><div className="badges"><span>Command</span><span>No client/WZ edit</span><span>Restart</span></div><h2>@{command.name}</h2><code>{command.implementation}</code><p>{command.description}</p></div>
  <section><h3>Access policy</h3><label className="toggle"><input type="checkbox" checked={enabled} onChange={e=>setEnabled(e.target.checked)}/>Registered and visible in @commands</label>
   <label>Required access level<select className="edit" value={level} onChange={e=>setLevel(Number(e.target.value))}>{[0,1,2,3,4,5,6].map(x=><option key={x}>{x}</option>)}</select></label>
   <textarea value={reason} onChange={e=>setReason(e.target.value)}/>{error&&<div className="error">{error}</div>}<div className="actions"><button className="secondary" disabled={!command.overridden} onClick={reset}>Use source registration</button><button className="primary" onClick={save}>Save policy</button></div></section>
  <section><h3>Original source</h3><Source label="File" value={command.sourceFile}/><Source label="Original level" value={command.originalLevel}/><Source label="Fallback" value="Registered exactly as coded when CMS data is unavailable"/></section>
  <section className="compat ok"><h3>Client and WZ reflection</h3><strong>No client/WZ edit</strong><p>The command registry and generated in-game command list use the same effective policy at server startup.</p></section></aside>
}

function SettingCard({setting:s,open}:{setting:Setting;open:()=>void}){const c=compatibility[s.compatibility]||compatibility.SERVER_ONLY;return <button className="setting-card" onClick={open}><div className="setting-main"><div className="badges"><span>{s.category}</span><span className={c.className}>{c.label}</span>{!s.editable&&<span>Read only</span>}</div><h3>{s.display_name}</h3><code>{s.setting_key}</code><p>{s.description}</p></div><div className="setting-values"><small>Effective</small><strong>{String(s.effective_value??"—")}</strong>{s.override_value&&<em>Overridden</em>}<span>{s.apply_mode}</span></div><ChevronRight size={18}/></button>}

function SettingDock({setting:s,close,changed}:{setting:Setting;close:()=>void;changed:(s:Setting|null)=>void}){
 const [value,setValue]=useState(String(s.override_value??s.default_value??"")),[reason,setReason]=useState("Changed through Server CMS"),[busy,setBusy]=useState(false),[error,setError]=useState("");
 const c=compatibility[s.compatibility]||compatibility.SERVER_ONLY;
 const scopeId=Number(s.scope_id||0);
 async function save(){setBusy(true);setError("");try{const next=await api<Setting>(`/api/settings/${encodeURIComponent(s.setting_key)}?scopeId=${scopeId}`,{method:"PUT",body:JSON.stringify({value,reason})});changed({...s,...next,effective_value:value})}catch(x){setError((x as Error).message)}finally{setBusy(false)}}
 async function reset(){setBusy(true);try{await api(`/api/settings/${encodeURIComponent(s.setting_key)}?scopeId=${scopeId}&reason=${encodeURIComponent(reason)}`,{method:"DELETE"});changed({...s,override_value:null,effective_value:s.default_value})}finally{setBusy(false)}}
 return <aside className="drawer"><button className="close" onClick={close}><X/></button><div className="drawer-head"><div className="badges"><span>{s.origin_type}</span><span className={c.className}>{c.label}</span><span>{s.apply_mode}</span></div><h2>{s.display_name}</h2><code>{s.setting_key}</code><p>{s.description}</p></div>
  <section><h3>Effective values</h3><div className="value-grid"><Tile label="Original fallback" value={s.default_value}/><Tile label="CMS override" value={s.override_value??"Not set"}/><Tile label="Effective after restart" value={s.override_value??s.default_value}/><Tile label="Scope" value={s.scope_type==="WORLD"?`World ${scopeId}`:s.scope_type}/></div></section>
  <section><h3>{s.editable?"Edit override":"Bootstrap setting"}</h3>{s.editable&&(s.value_type==="BOOLEAN"?<select className="edit" value={value} onChange={e=>setValue(e.target.value)}><option>true</option><option>false</option></select>:<input className="edit" value={value} onChange={e=>setValue(e.target.value)}/>)}
   {!s.editable&&<p className="muted">This value is needed before the Server CMS database is available, so it remains in its original bootstrap source.</p>}
   {s.editable&&<><textarea value={reason} onChange={e=>setReason(e.target.value)} placeholder="Audit reason"/>{error&&<div className="error">{error}</div>}<div className="actions"><button className="secondary" disabled={!s.override_value||busy} onClick={reset}><RotateCcw size={15}/>Use original</button><button className="primary" disabled={busy} onClick={save}>{busy?"Saving...":"Save override"}</button></div></>}</section>
  <section><h3>Original source</h3><Source label="Origin" value={s.origin_type}/><Source label="File" value={s.source_file}/><Source label="Symbol / key" value={s.source_symbol}/>{s.source_excerpt&&<pre>{s.source_excerpt}</pre>}</section>
  <section><h3>Server CMS implementation</h3><Source label="Implemented in" value={s.implementation_files||"Catalog metadata only"}/><Source label="Apply mode" value={s.apply_mode}/><Source label="Risk" value={s.risk_level}/></section>
  <section className={`compat ${c.className}`}><h3>Client and WZ reflection</h3><strong>{c.label}</strong><p>{s.compatibility_note||"No client or WZ change is required for this setting to behave and display as designed."}</p></section></aside>
}

function Audit({refreshKey}:{refreshKey:number}){const [rows,setRows]=useState<any[]>([]);useEffect(()=>{api<any[]>("/api/audit").then(setRows)},[refreshKey]);return <article className="panel"><Title title="Audit & rollback" sub="Exact changes, reasons and outcomes"/>{rows.map(r=><details className="audit-row" key={r.id}><summary><strong>{r.action}</strong><code>{r.entity_key}</code><span>{r.username||"System"} · {String(r.created_at)}</span></summary><p>{r.reason}</p><pre>{r.before_json||"Original fallback"} → {r.after_json||"Original fallback"}</pre></details>)}</article>}
function Loading(){return <div className="splash">Loading server state...</div>}
function Title({title,sub}:{title:string;sub:string}){return <div className="title"><h2>{title}</h2><p>{sub}</p></div>}
function Status({label,value}:{label:string;value:string}){return <div className="status"><span>{label}</span><strong>{value}</strong></div>}
function Tile({label,value}:{label:string;value:any}){return <div><small>{label}</small><strong>{String(value??"—")}</strong></div>}
function Source({label,value}:{label:string;value:any}){return <div className="source"><span>{label}</span><code>{String(value??"Not specified")}</code></div>}
