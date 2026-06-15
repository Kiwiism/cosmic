"use client";
import {Activity,BookOpen,ChevronRight,Clock3,Database,FileCode2,Gauge,History,Network,RotateCcw,Server,Settings2,ShieldCheck,SlidersHorizontal,Wrench,X} from "lucide-react";
import {useEffect,useState} from "react";
import {CmsAuth,CmsConnectionError} from "../components/auth/CmsAuth";
import {CmsNavItem,CmsShell} from "../components/layout/CmsShell";
import {PageToolbar} from "../components/ui/PageToolbar";
import {api} from "../lib/api";

type View="overview"|"settings"|"worlds"|"commands"|"security"|"performance"|"maintenance"|"diagnostics"|"deployments"|"audit";
type Setting=Record<string,any>;
type WorldSummary={id:number;name:string;active:boolean};
const nav:readonly CmsNavItem<View>[]=[
 {key:"overview",label:"Overview",icon:Activity},{key:"settings",label:"Features & general",icon:Settings2},{key:"worlds",label:"Worlds & rates",icon:Gauge},
 {key:"commands",label:"Commands",icon:SlidersHorizontal},{key:"security",label:"Access & security",icon:ShieldCheck},
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
   {view!=="overview"&&view!=="settings"&&view!=="worlds"&&view!=="commands"&&view!=="audit"&&<CategoryPage view={view} refreshKey={settingsVersion} onOpen={setDrawer}/>}
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
