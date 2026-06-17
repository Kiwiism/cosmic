"use client";
import {Activity,Coins,Network,RadioTower,UsersRound} from "lucide-react";
import {useEffect,useState} from "react";
import {CmsAuth,CmsConnectionError} from "../components/auth/CmsAuth";
import {CmsNavItem,CmsShell} from "../components/layout/CmsShell";
import {PageToolbar} from "../components/ui/PageToolbar";
import {api} from "../lib/api";

type View="agents"|"runtime"|"social"|"economy";
const nav:readonly CmsNavItem<View>[]=[
 {key:"agents",label:"Agents",icon:Network},
 {key:"runtime",label:"Runtime",icon:RadioTower},
 {key:"social",label:"Social",icon:UsersRound},
 {key:"economy",label:"Economy",icon:Coins}
];

export default function App(){
 const [auth,setAuth]=useState<boolean|null>(null),[setup,setSetup]=useState(false),[startupError,setStartupError]=useState("");
 const [view,setView]=useState<View>("agents");
 useEffect(()=>{api("/api/auth/me").then(()=>setAuth(true)).catch(async()=>{
  try{const status=await api<{required:boolean}>("/api/setup/status");setSetup(status.required);setAuth(false)}
  catch{setStartupError("The Agent CMS API is offline or its database credentials are not configured.");setAuth(false)}
 })},[]);
 if(auth===null)return <div className="splash">Preparing Cosmic Agent CMS...</div>;
 if(startupError)return <CmsConnectionError mark="AC" productName="Agent CMS" message={startupError}/>;
 if(!auth)return <CmsAuth mark="AC" productName="Agent CMS" secureLabel="AGENT OPERATIONS" setup={setup} onReady={()=>setAuth(true)}/>;
 return <CmsShell activeView={view} brandMark="AC" brandSubtitle="Agent CMS" eyebrow="COSMIC AGENT CENTER"
  headerStatus="Agent operations only" inspectorOpen={false} navigation={nav}
  sidebarStatus={<><Activity size={14}/>Agent runtime console</>}
  onNavigate={setView}>{view==="agents"?<Agents/>:view==="runtime"?<Runtime/>:view==="social"?<Social/>:<Economy/>}</CmsShell>
}

function Title({title,sub}:{title:string;sub?:string}){return <div className="title"><p className="eyebrow">COSMIC AGENT CENTER</p><h2>{title}</h2>{sub&&<p>{sub}</p>}</div>}
function Tile({label,value}:{label:string;value:any}){return <div><small>{label}</small><strong>{String(value??"unset")}</strong></div>}

function Agents(){
 const [agents,setAgents]=useState<any[]>([]),[selected,setSelected]=useState<any|null>(null),[q,setQ]=useState(""),[charQ,setCharQ]=useState(""),[chars,setChars]=useState<any[]>([]),[plan,setPlan]=useState<any|null>(null),[logs,setLogs]=useState<any[]>([]),[memory,setMemory]=useState<any[]>([]),[goals,setGoals]=useState<any[]>([]),[policies,setPolicies]=useState<any[]>([]),[scripts,setScripts]=useState<any[]>([]),[editingScript,setEditingScript]=useState<any|null>(null),[runtimeResult,setRuntimeResult]=useState<any|null>(null),[goalDraft,setGoalDraft]=useState({goalType:"IDLE",priority:0,targetMap:"",targetRef:""}),[scriptDraft,setScriptDraft]=useState({name:"idle-town",version:1,enabled:true,scriptType:"TEXT",body:"IDLE 30"}),[reason,setReason]=useState("Updated through Agent CMS"),[error,setError]=useState("");
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
 const parseDetails=(value:any)=>{try{return value?JSON.parse(String(value)):null}catch{return null}};
 const goalProgress=(goal:any)=>parseDetails(goal.progress_json);
 const latestRouteMemory=memory.find(item=>item.event_type==="NAVIGATION_ROUTE");
 const latestRoute=parseDetails(latestRouteMemory?.details_json);
 const route=latestRoute?.route;
 return <><article className="panel intro"><Title title="Agent operations" sub="Profiles, goals, scripts, policies, route state, memory and runtime controls for server-side agents."/></article>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search agent, account, character or ID"/>
  {error&&<div className="error">{error}</div>}
  <div className="agent-layout"><section className="panel"><Title title="Agent roster" sub={`${agents.length} profiles. Disabled profiles never enter the runtime registry.`}/>
   <div className="agent-list">{agents.map(agent=><button className={`agent-card ${selected?.id===agent.id?"selected":""}`} key={agent.id} onClick={()=>setSelected(agent)}>
    <div><strong>{agent.display_name||agent.character_name}</strong><small>{agent.account_name} {"->"} {agent.character_name} | Lv. {agent.level} | World {agent.world}</small></div>
    <span className={agent.enabled?"active-state":"inactive-state"}>{agent.enabled?"Enabled":"Disabled"}</span></button>)}</div></section>
   <section className="panel"><Title title="Create from character" sub="Regular Cosmic characters can be marked as future agents without changing their account."/>
    <div className="search compact"><Network size={17}/><input value={charQ} onChange={e=>setCharQ(e.target.value)} placeholder="Search account, IGN or character ID"/></div>
    <div className="agent-list compact-list">{chars.map(character=><button className="agent-card" disabled={Boolean(character.agent_profile_id)} key={character.id} onClick={()=>createAgent(character.id)}>
     <div><strong>{character.account_name} {"->"} {character.character_name}</strong><small>Lv. {character.level} | Job {character.job} | World {character.world} | Map {character.map}</small></div>
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
   <section className="panel"><Title title="Control preflight" sub="Prepare, enter, dry-run tick and release use the Cosmic bridge when enabled."/>
    {plan?<><div className="value-grid"><Tile label="Ready" value={String(plan.ready)}/><Tile label="World" value={plan.world}/><Tile label="Channel" value={plan.channel}/><Tile label="Map" value={plan.mapId}/><Tile label="Spawn point" value={plan.spawnPoint}/><Tile label="Message" value={plan.message}/></div>
     <div className="actions runtime-actions"><button className="secondary" onClick={()=>runtimeAction("prepare")}>Prepare</button><button className="secondary" onClick={()=>runtimeAction("enter")}>Enter world</button><button className="secondary" onClick={()=>runtimeAction("tick")}>Dry-run tick</button><button className="danger" onClick={()=>runtimeAction("release")}>Release</button></div>
     {runtimeResult&&<pre className="code-box">{JSON.stringify(runtimeResult,null,2)}</pre>}</>:<p className="muted">Select an agent to view preflight state.</p>}</section>
   <section className="panel full-span"><Title title="Goals" sub="Dry-run planner reads the highest priority active goal before falling back to scripts."/>
    <div className="filters compact"><select value={goalDraft.goalType} onChange={e=>setGoalDraft({...goalDraft,goalType:e.target.value})}><option>IDLE</option><option>WAIT</option><option>ROAM</option><option>MOVE_TO_MAP</option><option>GRIND_TO_LEVEL</option><option>LOOT</option><option>NPC</option><option>SHOP</option><option>SAY</option><option>USE_ITEM</option></select><input className="edit" type="number" value={goalDraft.priority} onChange={e=>setGoalDraft({...goalDraft,priority:Number(e.target.value)})} placeholder="Priority"/><input className="edit" value={goalDraft.targetMap} onChange={e=>setGoalDraft({...goalDraft,targetMap:e.target.value})} placeholder="Target map"/><input className="edit" value={goalDraft.targetRef} onChange={e=>setGoalDraft({...goalDraft,targetRef:e.target.value})} placeholder="Target ref"/><button className="primary" onClick={createGoal}>Add goal</button></div>
    {goals.length?goals.map(goal=>{const progress=goalProgress(goal);return <div className="audit-row" key={goal.id}><strong>{goal.goal_type}</strong><code>{goal.status} | priority {goal.priority}</code><span>{String(goal.updated_at)}</span><p>Target map {goal.target_map??"any"} | ref {goal.target_ref??"none"}</p>{progress&&<p>Last tick: {progress.lastIntent}{progress.lastArgument?` ${progress.lastArgument}`:""} | dispatch {progress.dispatchStatus} | map {progress.mapId} | mobs {progress.monsters} | drops {progress.drops}</p>}<div className="policy-actions"><button className="secondary" onClick={()=>setGoalStatus(goal,"ACTIVE")}>Activate</button><button className="secondary" onClick={()=>setGoalStatus(goal,"PAUSED")}>Pause</button><button className="secondary" onClick={()=>setGoalStatus(goal,"COMPLETED")}>Complete</button><button className="secondary" onClick={()=>setGoalStatus(goal,"FAILED")}>Fail</button><button className="danger" onClick={()=>setGoalStatus(goal,"CANCELLED")}>Cancel</button></div>{goal.progress_json&&<details><summary>Progress JSON</summary><pre>{goal.progress_json}</pre></details>}</div>}):<p className="muted">No goals yet. Without a goal the agent falls back to script/default idle planning.</p>}</section>
   <section className="panel full-span"><Title title="Navigation route state" sub="Latest dry-run route preview recorded by the server-side navigation adapter."/>
    {route?<div className="route-panel"><div className="value-grid"><Tile label="State" value={route.routeState||"unknown"}/><Tile label="From map" value={route.fromMapId}/><Tile label="Target map" value={route.toMapId}/><Tile label="Steps" value={route.stepCount}/><Tile label="Dispatch" value={latestRoute.dispatchStatus}/><Tile label="Recorded" value={latestRouteMemory?.created_at}/></div><p className="muted">{latestRoute.dispatchMessage||route.message}</p>{route.nextStep&&<article className="route-next"><strong>Next portal</strong><span>{route.nextStep.portalName} on map {route.nextStep.fromMapId} -&gt; {route.nextStep.toMapId}</span><small>x {route.nextStep.position?.x??0}, y {route.nextStep.position?.y??0} | {route.nextStep.open?"open":"closed"} | {route.nextStep.scripted?"scripted":"plain portal"}</small></article>}{Array.isArray(route.steps)&&route.steps.length?<ol className="route-steps">{route.steps.map((step:any,index:number)=><li key={`${step.fromMapId}-${step.portalId}-${index}`}><strong>{index+1}. {step.fromMapName||step.fromMapId}</strong><span>Portal {step.portalName} -&gt; map {step.toMapId}</span><small>x {step.position?.x??0}, y {step.position?.y??0} | type {step.portalType} | {step.open?"open":"closed"} | {step.scripted?"scripted":"plain"}</small></li>)}</ol>:<p className="muted">No route steps recorded.</p>}<details><summary>Raw route JSON</summary><pre>{JSON.stringify(latestRoute,null,2)}</pre></details></div>:<p className="muted">No route state yet. Add or activate a MOVE_TO_MAP goal, allow navigation policy, enter the agent, then run a dry-run tick.</p>}</section>
   <section className="panel full-span"><Title title="Capability policies" sub="Per-agent policy gates. Enabling a capability only passes policy; unimplemented runtime handlers still block gameplay actions."/>
    <div className="policy-grid">{policies.map(policy=><article className="policy-card" key={policy.key}><div><strong>{policy.label}</strong><code>{policy.key}</code><p>{policy.description}</p><small>Default {String(policy.defaultEnabled)} | Global {policy.globalValue??"unset"} | Agent {policy.agentValue??"unset"}</small></div><span className={policy.effective?"active-state":"inactive-state"}>{policy.effective?"Allowed":"Blocked"}</span><div className="policy-actions"><button className="secondary" onClick={()=>setPolicy(policy,true)}>Allow</button><button className="secondary" onClick={()=>setPolicy(policy,false)}>Block</button><button className="secondary" disabled={!policy.overridden} onClick={()=>resetPolicy(policy)}>Use fallback</button></div></article>)}</div></section>
   <section className="panel full-span"><Title title="Script library" sub="Reusable dry-run behavior scripts."/>
    <div className="filters compact"><input className="edit" value={scriptDraft.name} onChange={e=>setScriptDraft({...scriptDraft,name:e.target.value})} placeholder="Script name"/><input className="edit" type="number" value={scriptDraft.version} onChange={e=>setScriptDraft({...scriptDraft,version:Number(e.target.value)})} placeholder="Version"/><select value={String(scriptDraft.enabled)} onChange={e=>setScriptDraft({...scriptDraft,enabled:e.target.value==="true"})}><option value="true">enabled</option><option value="false">disabled</option></select><button className="primary" onClick={()=>saveScript(scriptDraft)}>Create script</button></div><textarea className="script-editor" value={scriptDraft.body} onChange={e=>setScriptDraft({...scriptDraft,body:e.target.value})}/>
    {scripts.length?scripts.map(script=><div className="audit-row" key={script.id}><strong>{script.name}</strong><code>v{script.version} | {script.enabled?"enabled":"disabled"} | {script.script_type}</code><span>{String(script.updated_at)}</span>{editingScript?.id===script.id?<><textarea className="script-editor" value={editingScript.body||""} onChange={e=>setEditingScript({...editingScript,body:e.target.value})}/><div className="filters compact"><input className="edit" value={editingScript.name||""} onChange={e=>setEditingScript({...editingScript,name:e.target.value})}/><input className="edit" type="number" value={editingScript.version||1} onChange={e=>setEditingScript({...editingScript,version:Number(e.target.value)})}/><button className="secondary" onClick={()=>setEditingScript(null)}>Cancel</button><button className="primary" onClick={()=>saveScript(editingScript)}>Save script</button></div></>:<><p>{String(script.body||"").split(/\r?\n/).slice(0,3).join(" | ")}</p><div className="policy-actions"><button className="secondary" onClick={()=>setEditingScript(script)}>Edit</button><button className="secondary" onClick={()=>setSelected(selected?{...selected,script_name:script.name}:selected)}>Use on selected agent</button></div></>}</div>):<p className="muted">No reusable scripts yet.</p>}</section>
   <section className="panel full-span"><Title title="Recent memory" sub="Compact observation checkpoints recorded during dry-run ticks."/>
    {memory.length?memory.map(item=><div className="audit-row" key={item.id}><strong>{item.event_type}</strong><code>Importance {item.importance}</code><span>{String(item.created_at)}</span><p>{item.summary}</p>{item.details_json&&<details><summary>Details</summary><pre>{item.details_json}</pre></details>}</div>):<p className="muted">No memory events yet. Run a dry-run tick after entering an agent.</p>}</section>
   <section className="panel full-span"><Title title="Recent action logs" sub="Lifecycle and future action records from agent_action_logs"/>
    {logs.length?logs.map(log=><div className="audit-row" key={log.id}><strong>{log.action_type}</strong><code>{log.status}</code><span>{String(log.created_at)}</span><p>{log.message}</p></div>):<p className="muted">No runtime logs yet.</p>}</section></div>}</>
}

function Runtime(){
 const [q,setQ]=useState(""),[sessions,setSessions]=useState<any[]>([]),[error,setError]=useState("");
 useEffect(()=>{const timer=setTimeout(()=>{api<any[]>(`/api/agents/runtime/sessions?q=${encodeURIComponent(q)}`).then(setSessions).catch(x=>setError((x as Error).message))},160);return()=>clearTimeout(timer)},[q]);
 const active=sessions.filter(row=>!row.ended_at).length;
 return <><article className="panel intro"><Title title="Runtime sessions" sub="Live and historical runtime shells. This is read-only observability for agent lifecycle state."/></article>
  <div className="metrics compact-metrics"><article><RadioTower/><small>Open sessions</small><strong>{active}</strong></article><article><Activity/><small>Loaded rows</small><strong>{sessions.length}</strong></article></div>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search agent, character, state or task"/>
  {error&&<div className="error">{error}</div>}
  <section className="panel"><Title title="Session ledger" sub="Newest runtime sessions first."/>
   <div className="ledger-list">{sessions.map(session=><article className="ledger-card" key={session.id}>
    <div><strong>{session.display_name||session.character_name}</strong><small>{session.account_name} {"->"} {session.character_name}</small></div>
    <span className={!session.ended_at?"active-state":"inactive-state"}>{session.state}</span>
    <div className="ledger-grid"><Tile label="World" value={session.world}/><Tile label="Channel" value={session.channel}/><Tile label="Map" value={session.map_id}/><Tile label="Task" value={session.current_task||"none"}/><Tile label="Started" value={session.started_at}/><Tile label="Last tick" value={session.last_tick_at||"never"}/><Tile label="Ended" value={session.ended_at||"open"}/><Tile label="Stop reason" value={session.stop_reason||"none"}/></div>
   </article>)}</div>{!sessions.length&&<p className="muted">No runtime sessions recorded yet.</p>}</section></>
}

function Social(){
 const [q,setQ]=useState(""),[relationships,setRelationships]=useState<any[]>([]),[chat,setChat]=useState<any[]>([]),[error,setError]=useState("");
 useEffect(()=>{const timer=setTimeout(()=>{api<any[]>(`/api/agents/social/relationships?q=${encodeURIComponent(q)}`).then(setRelationships).catch(x=>setError((x as Error).message));api<any[]>(`/api/agents/social/chat?q=${encodeURIComponent(q)}`).then(setChat).catch(x=>setError((x as Error).message))},160);return()=>clearTimeout(timer)},[q]);
 return <><article className="panel intro"><Title title="Social memory" sub="Relationships and chat logs, ready for future party/friend/companion behavior."/></article>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search agent, player, relationship, or message"/>
  {error&&<div className="error">{error}</div>}
  <div className="agent-detail"><section className="panel"><Title title="Relationships" sub={`${relationships.length} relationship records.`}/>
   <div className="ledger-list">{relationships.map(row=><article className="ledger-card social-card" key={row.id}>
    <div><strong>{row.display_name||row.agent_character_name} {"->"} {row.related_character_name}</strong><small>{row.related_account_name} | {row.relationship_type}</small></div>
    <div className="ledger-grid"><Tile label="Trust" value={row.trust_score}/><Tile label="Affinity" value={row.affinity_score}/><Tile label="Updated" value={row.updated_at}/><Tile label="Notes" value={row.notes||"none"}/></div>
   </article>)}</div>{!relationships.length&&<p className="muted">No relationship records yet.</p>}</section>
   <section className="panel"><Title title="Chat logs" sub={`${chat.length} chat records.`}/>
   <div className="ledger-list">{chat.map(row=><article className="ledger-card social-card" key={row.id}>
    <div><strong>{row.display_name||row.agent_character_name}</strong><small>{row.channel_type} | {row.direction} | {row.created_at}</small></div>
    <p>{row.message}</p><small className="muted">Sender {row.sender_name||row.sender_character_id||"unknown"} | Recipient {row.recipient_name||row.recipient_character_id||"none"}</small>
   </article>)}</div>{!chat.length&&<p className="muted">No chat records yet.</p>}</section></div></>
}

function Economy(){
 const [q,setQ]=useState(""),[ledger,setLedger]=useState<any[]>([]),[error,setError]=useState("");
 useEffect(()=>{const timer=setTimeout(()=>{api<any[]>(`/api/agents/economy/ledger?q=${encodeURIComponent(q)}`).then(setLedger).catch(x=>setError((x as Error).message))},160);return()=>clearTimeout(timer)},[q]);
 const meso=ledger.reduce((sum,row)=>sum+Number(row.meso_delta||0),0);
 return <><article className="panel intro"><Title title="Economy ledger" sub="Item, meso, source and counterparty records for future trading, looting, merchant and reward behavior."/></article>
  <div className="metrics compact-metrics"><article><Coins/><small>Loaded entries</small><strong>{ledger.length}</strong></article><article><Coins/><small>Net meso delta</small><strong>{meso.toLocaleString()}</strong></article></div>
  <PageToolbar query={q} onQueryChange={setQ} placeholder="Search agent, item ID, entry type, source, or counterparty"/>
  {error&&<div className="error">{error}</div>}
  <section className="panel"><Title title="Ledger entries" sub="Newest economy records first."/>
   <div className="ledger-list">{ledger.map(row=><article className="ledger-card" key={row.id}>
    <div><strong>{row.display_name||row.agent_character_name}</strong><small>{row.entry_type} | {row.created_at}</small></div>
    <span className={Number(row.meso_delta||0)>=0?"active-state":"inactive-state"}>{Number(row.meso_delta||0).toLocaleString()} meso</span>
    <div className="ledger-grid"><Tile label="Item" value={row.item_id||"none"}/><Tile label="Quantity" value={row.quantity}/><Tile label="Source" value={`${row.source_type||"none"} ${row.source_id||""}`}/><Tile label="Counterparty" value={row.counterparty_name||row.counterparty_character_id||"none"}/><Tile label="World" value={row.world??"unset"}/><Tile label="Map" value={row.map_id??"unset"}/></div>
    {row.details_json&&<details><summary>Details JSON</summary><pre>{row.details_json}</pre></details>}
   </article>)}</div>{!ledger.length&&<p className="muted">No economy ledger entries yet.</p>}</section></>
}
