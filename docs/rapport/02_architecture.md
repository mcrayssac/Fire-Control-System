# 2. Architecture du systeme FCS

## 2.1 Justification du choix

Le **Fire Control System** reunit toutes les caracteristiques d'un systeme critique distribue :
- **Criticite** : erreur dans la sequence de tir = consequences irreversibles
- **Concurrence** : capteur, tracking, munitions et autorisation operent en parallele
- **Synchronisation** : le tir exige que toutes les preconditions soient reunies
- **Ressources finies** : stock de munitions limite
- **Tolerance aux pannes** : le systeme doit se remettre d'erreurs sans blocage

---

## 2.2 Architecture acteurs

8 acteurs types Akka organises en hierarchie de supervision :

```
SupervisorActor (racine)
|-- SensorActor            -- Detection de cibles
|-- TrackingActor          -- Calcul balistique
|-- AmmoActor              -- Gestion du stock de munitions
|-- CommandActor           -- Validation des ROE
|-- FireControlActor       -- Orchestrateur central
|-- KafkaProducerActor     -- Publication d'evenements
+-- KafkaConsumerActor     -- Audit trail
```

### Role de chaque acteur

| Acteur | Responsabilite |
|---|---|
| **SupervisorActor** | Creation, supervision et restart des acteurs enfants |
| **SensorActor** | Detection de cibles, declenchement du cycle de tir |
| **TrackingActor** | Calcul balistique (elevation, derive, temps de vol, confiance) |
| **AmmoActor** | Stock de munitions (APFSDS, HEAT, HESH, HE), chargement |
| **CommandActor** | Validation des regles d'engagement (confiance min, portee max) |
| **FireControlActor** | Orchestrateur : attend les 3 confirmations, execute le tir, gere reload/cooldown |
| **KafkaProducerActor** | Publication sur 7 topics Kafka |
| **KafkaConsumerActor** | Collecte de l'audit trail |

---

## 2.3 Flux du cycle de tir nominal

```
SensorActor              FireControlActor             TrackingActor
    |                          |                            |
    |  SimulateDetection       |                            |
    |------------------------->|  TrackTarget               |
    |                          |--------------------------->|
    |                          |  TargetLockConfirmed       |
    |                          |<---------------------------|
    |                          |
    |                          |  LoadAmmo ---------> AmmoActor
    |                          |  AmmoLoadConfirmed <-------'
    |                          |
    |                          |  RequestAuth ------> CommandActor
    |                          |  FireAuthConfirmed <-------'
    |                          |
    |                          |  [readyToFire = true]
    |                          |  executeFire()
    |                          |  PublishEvent ------> KafkaProducerActor
    |                          |
    |                          |  ReloadTimeout (2s)
    |                          |  CooldownTimeout (3s)
    |                          |  -> retour a Idle
```

### Point de synchronisation

Le **FireControlActor** attend trois confirmations avant d'autoriser le tir :
1. **TargetLockConfirmed** : verrouillage balistique reussi
2. **AmmoLoadConfirmed** : munition chargee
3. **FireAuthConfirmed** : regles d'engagement validees

Condition : `readyToFire = targetLocked AND ammoLoaded AND fireAuthorized`

Cela correspond a la **transition T4** du reseau de Petri (synchronisation P3 + P4 -> P5).

---

## 2.4 Gestion des erreurs

| Situation | Comportement |
|---|---|
| Echec verrouillage (confiance < 0.5) | TrackingActor -> `TargetLockFailed` -> cycle avorte |
| Refus autorisation (confiance < 0.8 ou portee > 4000m) | CommandActor -> `FireAuthDenied` -> cycle avorte |
| Stock epuise | AmmoActor -> `AmmoLoadFailed` -> cycle avorte |
| Erreur systeme | SupervisorActor -> `ReportError` -> strategie restart |

---

## 2.5 Topics Kafka

| Topic | Evenement | Producteur |
|---|---|---|
| `fcs.target.detected` | Cible detectee | SensorActor |
| `fcs.target.locked` | Solution balistique | TrackingActor |
| `fcs.fire.authorized` | ROE validees | CommandActor |
| `fcs.fire.executed` | Tir effectue | FireControlActor |
| `fcs.ammo.status` | Munition chargee | AmmoActor |
| `fcs.error.critical` | Erreur fatale | SupervisorActor |
| `fcs.audit.log` | Journal | KafkaProducerActor |
