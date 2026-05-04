# 3. Modele formel -- Reseau de Petri du FCS

## 3.1 Definition formelle

**N = (P, T, Pre, Post, M0)** avec |P| = 13 places, |T| = 12 transitions.

**M0** = (1, 0, 0, 0, 0, 0, 0, 0, 0, 0, n, 0, 0) ou n = stock initial de munitions.

### Places

| Place | Nom | Type | Role |
|:---:|---|---|---|
| P0 | Idle | Controle | Systeme au repos |
| P1 | Target_Detected | Controle | Cible detectee |
| P2 | Target_Locked | Controle | Verrouillage confirme |
| P3 | Ammo_Loaded | Controle | Munition chargee |
| P4 | Fire_Authorized | Controle | Autorisation accordee |
| P5 | Ready_To_Fire | Controle | Preconditions reunies |
| P6 | Firing | Controle | Tir en cours |
| P7 | Reloading | Controle | Rechargement |
| P8 | Cooldown | Controle | Refroidissement |
| P9 | Error_State | Controle | Erreur detectee |
| P10 | Ammo_Stock | Ressource | Stock de munitions |
| P11 | Kafka_Queue | Ressource | Evenements en attente |
| P12 | Log_Recorded | Ressource | Evenements journalises |

### Transitions

| Transition | Nom | Pre -> Post |
|:---:|---|---|
| T0 | detect_target | {P0, P10} -> {P1, P10, P11} |
| T1 | lock_target | {P1} -> {P2} |
| T2 | load_ammo | {P10} -> {P3} |
| T3 | authorize_fire | {P2} -> {P4} |
| T4 | ready_sync | {P3, P4} -> {P5} |
| T5 | fire | {P5} -> {P6, P11} |
| T6 | end_fire | {P6} -> {P7} |
| T7 | reload_complete | {P7} -> {P8} |
| T8 | cooldown_complete | {P8} -> {P0} |
| T9 | error_detected | {P0} -> {P9} |
| T10 | error_recovery | {P9} -> {P0} |
| T11 | kafka_log | {P11} -> {P12} |

![Reseau de Petri du FCS](../fcs_petri_net.drawio.png)

---

## 3.2 Correspondance Acteur Akka <-> Reseau de Petri

| Acteur Akka | Places | Transitions |
|---|---|---|
| SensorActor | P0, P1 | T0 |
| TrackingActor | P1 -> P2 | T1 |
| AmmoActor | P10, P3 | T2 |
| CommandActor | P2 -> P4 | T3 |
| FireControlActor | P3+P4 -> P5 -> P6 -> P7 -> P8 -> P0 | T4, T5, T6, T7, T8 |
| SupervisorActor | P0 <-> P9 | T9, T10 |
| KafkaProducerActor | P11 -> P12 | T11 |

### Cycle nominal pas a pas

```
Etape  Transition  Acteur                Message Akka                  Effet Petri Net
  1    T0          SensorActor           SimulateDetection             P0,P10 -> P1,P10,P11
  2    T1          TrackingActor         TargetLockConfirmed           P1 -> P2
  3    T2          AmmoActor             AmmoLoadConfirmed             P10 -> P3
  4    T3          CommandActor          FireAuthConfirmed             P2 -> P4
  5    T4          FireControlActor      readyToFire = true            P3,P4 -> P5
  6    T5          FireControlActor      executeFire()                 P5 -> P6,P11
  7    T6          FireControlActor      (interne)                     P6 -> P7
  8    T7          FireControlActor      ReloadTimeout                 P7 -> P8
  9    T8          FireControlActor      CooldownTimeout               P8 -> P0
 10    T11         KafkaProducerActor    PublishEvent (x2)             P11 -> P12
```

Les etapes 2, 3 et 4 sont **concurrentes** dans Akka. La transition T4 (synchronisation) les rejoint.

---

## 3.3 Matrice d'incidence

C[place, transition] = Post - Pre :

```
                   T0   T1   T2   T3   T4   T5   T6   T7   T8   T9   T10  T11
Idle               -1    .    .    .    .    .    .    .   +1   -1   +1    .
Target_Detected    +1   -1    .    .    .    .    .    .    .    .    .    .
Target_Locked       .   +1    .   -1    .    .    .    .    .    .    .    .
Ammo_Loaded         .    .   +1    .   -1    .    .    .    .    .    .    .
Fire_Authorized     .    .    .   +1   -1    .    .    .    .    .    .    .
Ready_To_Fire       .    .    .    .   +1   -1    .    .    .    .    .    .
Firing              .    .    .    .    .   +1   -1    .    .    .    .    .
Reloading           .    .    .    .    .    .   +1   -1    .    .    .    .
Cooldown            .    .    .    .    .    .    .   +1   -1    .    .    .
Error_State         .    .    .    .    .    .    .    .    .   +1   -1    .
Ammo_Stock          .    .   -1    .    .    .    .    .    .    .    .    .
Kafka_Queue        +1    .    .    .    .   +1    .    .    .    .    .   -1
Log_Recorded        .    .    .    .    .    .    .    .    .    .    .   +1
```

**Lecture** : colonne T2 -> P10 perd 1 jeton (-1), P3 en gagne 1 (+1) = consommation de munition.

---

## 3.4 Elements non modelises

| Aspect Akka | Raison de l'abstraction |
|---|---|
| Types de munitions (APFSDS, HEAT, HESH, HE) | Stock generique (P10) -- n'affecte pas la surete |
| Calcul balistique detaille | Abstrait en T1 (succes/echec) |
| Timeouts (tracking 5s, reload 2s, cooldown 3s) | Reseau non temporise |
| Scenarios multiples du Supervisor | Cycle generique |
| Supervision et restart Akka | Abstrait par T9 -> T10 |

Le diagramme complet est disponible en version source dans `docs/fcs_petri_net.drawio` et en version image dans `docs/fcs_petri_net.drawio.png`.
