"use client";
import {Activity,BookOpen,ChevronRight,CircleUserRound,Clock3,Database,FileCode2,Gauge,History,Network,RotateCcw,Search,Server,Settings2,ShieldCheck,SlidersHorizontal,Wrench,X} from "lucide-react";
import {FormEvent,useEffect,useMemo,useState} from "react";
import {api} from "../lib/api";

type View="overview"|"settings"|"worlds"|"commands"|"security"|"performance"|"maintenance"|"diagnostics"|"deployments"|"audit";
type Setting=Record<string,any>;
const nav:[View,string,any][]=[
 ["overview","Overview",Activity],["settings","Configuration",Settings2],["worlds","Worlds & rates",Gauge],
 ["commands","Commands",SlidersHorizontal],["security","Access & security",ShieldCheck],
 ["performance","Runtime & schedulers",Clock3],["maintenance","Maintenance",Wrench],
 ["diagnostics","Diagnostics & logs",Network],["deployments","Deployments",Database],["audit","Audit & rollback",History]
];
const compatibility:Record<string,{label:string,className:string}>={
 SERVER_ONLY:{label:"No client/WZ edit",className:"ok"},
 WZ_REQUIRED:{label:"WZ edit required",className:"wz"},
 CLIENT_REQUIRED:{label:"Client edit required",className:"client"},
 WZ_AND_CLIENT:{label:"WZ + client edit",className:"both"}
};

export default function App(){
 const [auth,setAuth]=useState<boolean|null>(null),[setup,setSetup]=useState(false),[view,setView]=useState<View>("overview"),[drawer,setDrawer]=useState<Setting|null>(null);
 useEffect(()=>{api("/api/auth/me").then(()=>setAuth(true)).catch(()=>api<{required:boolean}>("/api/setup/status").then(x=>{setSetup(x.required);setAuth(false)}).catch(()=>setAuth(false)))},[]);
 if(auth===null)return <div className="splash">Preparing Cosmic Server CMS...</div>;
 if(!auth)return <Auth setup={setup} ready={()=>setAuth(true)}/>;
 return <div className="shell"><aside><div className="brand"><div className="mark">SC</div><div><strong>Cosmic</strong><small>Server CMS</small></div></div>
  <nav>{nav.map(([k,label,Icon])=><button className={view===k?"active":""} key={k} onClick={()=>{setView(k);setDrawer(null)}}><Icon size={18}/>{label}</button>)}</nav>
  <div className="side-foot"><Server size={16}/> Independent operations console</div></aside>
  <main><header><div><p className="eyebrow">COSMIC CONTROL CENTER</p><h1>{nav.find(x=>x[0]===view)?.[1]}</h1></div><div className="head"><span>Server configuration only</span><CircleUserRound size={30}/></div></header>
  <section className="workspace">
   {view==="overview"&&<Overview open={s=>{setDrawer(s);setView("settings")}}/>}
   {view==="settings"&&<Configuration onOpen={setDrawer}/>}
   {view==="commands"&&<Commands/>}
   {view!=="overview"&&view!=="settings"&&view!=="commands"&&view!=="audit"&&<CategoryPage view={view} onOpen={setDrawer}/>}
   {view==="audit"&&<Audit/>}
  </section></main>{drawer&&<SettingDock setting={drawer} close={()=>setDrawer(null)} changed={setDrawer}/>}</div>
}

function Auth({setup,ready}:{setup:boolean;ready:()=>void}){
 const [error,setError]=useState("");
 async function submit(e:FormEvent<HTMLFormElement>){e.preventDefault();const f=new FormData(e.currentTarget);try{
  if(setup)await api("/api/setup",{method:"POST",body:JSON.stringify({username:f.get("username"),displayName:f.get("displayName"),password:f.get("password")})});
  await api("/api/auth/login",{method:"POST",body:JSON.stringify({username:f.get("username"),password:f.get("password")})});ready()
 }catch(x){setError((x as Error).message)}}
 return <div className="auth"><form onSubmit={submit}><div className="mark large">SC</div><p className="eyebrow">{setup?"FIRST RUN":"SECURE OPERATIONS"}</p>
  <h1>{setup?"Create Server CMS owner":"Server control center"}</h1>{setup&&<label>Display name<input name="displayName" defaultValue="admin" required/></label>}
  <label>Username<input name="username" defaultValue={setup?"admin":""} required autoFocus/></label><label>Password<input name="password" type="password" required minLength={setup?5:1}/></label>
  {error&&<div className="error">{error}</div>}<button className="primary">{setup?"Create owner":"Sign in"}<ChevronRight size={16}/></button></form></div>
}

function Overview({open}:{open:(s:Setting)=>void}){
 const [data,setData]=useState<any>();const [recent,setRecent]=useState<Setting[]>([]);
 useEffect(()=>{api("/api/dashboard").then(setData);api<Setting[]>("/api/settings").then(x=>setRecent(x.filter(s=>s.override_value).slice(0,6)))},[]);
 if(!data)return <Loading/>;
 const server=data.server||{};
 return <><div className={`server-banner ${server.status==="UP"?"up":"down"}`}><div className="pulse"/><div><strong>Cosmic server {String(server.status).toLowerCase()}</strong>
  <span>{server.status==="UP"?`${server.worlds} worlds · ${server.channels} channels · ${server.onlinePlayers} online`:"Overrides remain stored; Cosmic will fall back safely at startup"}</span></div></div>
  <div className="metrics">{[["Managed settings",data.settings,Settings2],["YAML origins",data.yamlSettings,BookOpen],["Hardcoded origins",data.hardcodedSettings,FileCode2],["New settings",data.newSettings,SlidersHorizontal],["Active overrides",data.overrides,Gauge],["Restart settings",data.restartSettings,RotateCcw]].map(([l,v,I]:any)=><article key={l}><I size={19}/><small>{l}</small><strong>{Number(v||0).toLocaleString()}</strong></article>)}</div>
  <div className="columns"><article className="panel"><Title title="Configuration readiness" sub="Desired state, runtime state and fallback remain distinct"/>
   <Status label="Server bridge" value={server.status||"OFFLINE"}/><Status label="Override database" value="Connected"/><Status label="Fallback chain" value="CMS override -> config.yaml -> Java default"/>
   <Status label="Client/WZ safeguards" value="Compatibility classified"/></article>
   <article className="panel dark"><p className="eyebrow">PROVENANCE FIRST</p><h2>Every setting remembers where it came from.</h2><p>Open any entry to see its original YAML key or Java source, implementation consumers, fallback, apply mode, and whether native gameplay display needs a client or WZ update.</p></article></div>
  <article className="panel"><Title title="Recent active overrides" sub="Only values diverging from original Cosmic behavior"/>{recent.length?recent.map(s=><button className="setting-row" onClick={()=>open(s)} key={s.setting_key}><div><strong>{s.display_name}</strong><code>{s.setting_key}</code></div><span>{s.override_value}</span><ChevronRight size={16}/></button>):<p className="muted">No overrides. Cosmic is using its original configuration.</p>}</article></>
}

function Configuration({onOpen}:{onOpen:(s:Setting)=>void}){
 const [rows,setRows]=useState<Setting[]>([]),[cats,setCats]=useState<string[]>([]),[q,setQ]=useState(""),[cat,setCat]=useState(""),[origin,setOrigin]=useState(""),[compat,setCompat]=useState("");
 useEffect(()=>{api<string[]>("/api/settings/categories").then(setCats)},[]);
 useEffect(()=>{const t=setTimeout(()=>{const p=new URLSearchParams({q,category:cat,origin,compatibility:compat});api<Setting[]>(`/api/settings?${p}`).then(setRows)},120);return()=>clearTimeout(t)},[q,cat,origin,compat]);
 return <><div className="filters"><div className="search"><Search size={18}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder="Search setting, key or description"/></div>
  <select value={cat} onChange={e=>setCat(e.target.value)}><option value="">All sections</option>{cats.map(x=><option key={x}>{x}</option>)}</select>
  <select value={origin} onChange={e=>setOrigin(e.target.value)}><option value="">All origins</option><option>YAML_EXISTING</option><option>JAVA_HARDCODED</option><option>SERVER_CMS_NEW</option></select>
  <select value={compat} onChange={e=>setCompat(e.target.value)}><option value="">All compatibility</option>{Object.keys(compatibility).map(x=><option key={x}>{x}</option>)}</select></div>
  <p className="count">{rows.length} settings</p><div className="setting-list">{rows.map(s=><SettingCard key={s.setting_key} setting={s} open={()=>onOpen(s)}/>)}</div></>
}

function CategoryPage({view,onOpen}:{view:View;onOpen:(s:Setting)=>void}){
 const map:Record<string,string[]>={worlds:["Worlds & Channels","Worlds & rates"],commands:["Commands"],security:["Authentication & Sessions","Security & Anti-Abuse"],performance:["Runtime & Performance","Schedulers"],maintenance:["Maintenance"],diagnostics:["Diagnostics & Logs"],deployments:["Network"]};
 const [rows,setRows]=useState<Setting[]>([]);useEffect(()=>{Promise.all((map[view]||[]).map(category=>api<Setting[]>(`/api/settings?category=${encodeURIComponent(category)}`))).then(x=>setRows(x.flat()))},[view]);
 return <><article className="panel intro"><Title title={nav.find(x=>x[0]===view)?.[1]||view} sub="Settings are grouped here without losing their original source and compatibility metadata."/></article><div className="setting-list">{rows.map(s=><SettingCard key={s.setting_key} setting={s} open={()=>onOpen(s)}/>)}</div>{!rows.length&&<article className="panel"><p className="muted">This operations module is scaffolded for the next managed controls. Use Configuration to browse the current catalog.</p></article>}</>
}

function Commands(){
 const [rows,setRows]=useState<any[]>([]),[q,setQ]=useState(""),[level,setLevel]=useState(""),[editing,setEditing]=useState<any|null>(null);
 const load=()=>api<any[]>(`/api/commands?q=${encodeURIComponent(q)}${level?`&level=${level}`:""}`).then(setRows);
 useEffect(()=>{const timer=setTimeout(load,120);return()=>clearTimeout(timer)},[q,level]);
 return <><div className="filters"><div className="search"><Search size={18}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder="Search command or purpose"/></div>
  <select value={level} onChange={e=>setLevel(e.target.value)}><option value="">All access levels</option>{[0,1,2,3,4,5,6].map(x=><option key={x} value={x}>Level {x}</option>)}</select></div>
  <p className="count">{rows.length} source-registered commands. Changes apply after restart and update the in-game @commands list.</p>
  <div className="command-grid">{rows.map(command=><button className={`command-card ${command.enabled?"":"disabled"}`} key={command.name} onClick={()=>setEditing(command)}>
   <div><strong>@{command.name}</strong><p>{command.description||command.implementation}</p><code>{command.implementation}</code></div>
   <div><span>Level {command.effectiveLevel}</span>{command.overridden&&<em>Overridden</em>}{!command.enabled&&<em>Hidden</em>}</div></button>)}</div>
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
 async function save(){setBusy(true);setError("");try{const next=await api<Setting>(`/api/settings/${encodeURIComponent(s.setting_key)}`,{method:"PUT",body:JSON.stringify({value,reason})});changed({...s,...next,effective_value:value})}catch(x){setError((x as Error).message)}finally{setBusy(false)}}
 async function reset(){setBusy(true);try{await api(`/api/settings/${encodeURIComponent(s.setting_key)}?reason=${encodeURIComponent(reason)}`,{method:"DELETE"});changed({...s,override_value:null,effective_value:s.default_value})}finally{setBusy(false)}}
 return <aside className="drawer"><button className="close" onClick={close}><X/></button><div className="drawer-head"><div className="badges"><span>{s.origin_type}</span><span className={c.className}>{c.label}</span><span>{s.apply_mode}</span></div><h2>{s.display_name}</h2><code>{s.setting_key}</code><p>{s.description}</p></div>
  <section><h3>Effective values</h3><div className="value-grid"><Tile label="Original fallback" value={s.default_value}/><Tile label="CMS override" value={s.override_value??"Not set"}/><Tile label="Effective after restart" value={s.override_value??s.default_value}/><Tile label="Scope" value={s.scope_type}/></div></section>
  <section><h3>{s.editable?"Edit override":"Bootstrap setting"}</h3>{s.editable&&(s.value_type==="BOOLEAN"?<select className="edit" value={value} onChange={e=>setValue(e.target.value)}><option>true</option><option>false</option></select>:<input className="edit" value={value} onChange={e=>setValue(e.target.value)}/>)}
   {!s.editable&&<p className="muted">This value is needed before the Server CMS database is available, so it remains in its original bootstrap source.</p>}
   {s.editable&&<><textarea value={reason} onChange={e=>setReason(e.target.value)} placeholder="Audit reason"/>{error&&<div className="error">{error}</div>}<div className="actions"><button className="secondary" disabled={!s.override_value||busy} onClick={reset}><RotateCcw size={15}/>Use original</button><button className="primary" disabled={busy} onClick={save}>{busy?"Saving...":"Save override"}</button></div></>}</section>
  <section><h3>Original source</h3><Source label="Origin" value={s.origin_type}/><Source label="File" value={s.source_file}/><Source label="Symbol / key" value={s.source_symbol}/>{s.source_excerpt&&<pre>{s.source_excerpt}</pre>}</section>
  <section><h3>Server CMS implementation</h3><Source label="Implemented in" value={s.implementation_files||"Catalog metadata only"}/><Source label="Apply mode" value={s.apply_mode}/><Source label="Risk" value={s.risk_level}/></section>
  <section className={`compat ${c.className}`}><h3>Client and WZ reflection</h3><strong>{c.label}</strong><p>{s.compatibility_note||"No client or WZ change is required for this setting to behave and display as designed."}</p></section></aside>
}

function Audit(){const [rows,setRows]=useState<any[]>([]);useEffect(()=>{api<any[]>("/api/audit").then(setRows)},[]);return <article className="panel"><Title title="Audit & rollback" sub="Exact changes, reasons and outcomes"/>{rows.map(r=><details className="audit-row" key={r.id}><summary><strong>{r.action}</strong><code>{r.entity_key}</code><span>{r.username||"System"} · {String(r.created_at)}</span></summary><p>{r.reason}</p><pre>{r.before_json||"Original fallback"} → {r.after_json||"Original fallback"}</pre></details>)}</article>}
function Loading(){return <div className="splash">Loading server state...</div>}
function Title({title,sub}:{title:string;sub:string}){return <div className="title"><h2>{title}</h2><p>{sub}</p></div>}
function Status({label,value}:{label:string;value:string}){return <div className="status"><span>{label}</span><strong>{value}</strong></div>}
function Tile({label,value}:{label:string;value:any}){return <div><small>{label}</small><strong>{String(value??"—")}</strong></div>}
function Source({label,value}:{label:string;value:any}){return <div className="source"><span>{label}</span><code>{String(value??"Not specified")}</code></div>}
