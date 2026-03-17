# 5. Simulation comparee -- Petri Net vs Akka

## 5.1 Methodologie

La simulation comparee verifie que le **modele formel** (Petri Net) et l'**implementation** (Akka) produisent des comportements coherents.

**Approche :**
1. Execution deterministe de transitions sur le Petri Net avec enregistrement de la trace
2. Execution du systeme Akka avec les memes scenarios
3. Comparaison via le mapping transition <-> evenement Akka

### Mapping Transition -> Evenement Akka

| Transition | Evenement Akka |
|---|---|
| T0 detect_target | SensorActor -> TargetDetected -> InitiateFireCycle |
| T1 lock_target | TrackingActor -> TargetLockConfirmed |
| T2 load_ammo | AmmoActor -> AmmoLoadConfirmed |
| T3 authorize_fire | CommandActor -> FireAuthConfirmed |
| T4 ready_sync | FireControlActor.checkSync -> readyToFire |
| T5 fire | FireControlActor.executeFire -> FireExecuted |
| T6 end_fire | FireControlActor -> postFire |
| T7 reload_complete | FireControlActor -> ReloadTimeout |
| T8 cooldown_complete | FireControlActor -> CooldownTimeout -> Idle |
| T9 error_detected | SupervisorActor -> ReportError |
| T10 error_recovery | SupervisorStrategy.restart -> Idle |
| T11 kafka_log | KafkaProducerActor -> PublishEvent |

---

## 5.2 Resultats par scenario

### Scenario 1 : Cycle nominal

Sequence T0 -> T1 -> T2 -> T3 -> T4 -> T5 -> T6 -> T7 -> T8 -> T11 -> T11

Toutes les transitions s'executent sans blocage. Le systeme retourne a Idle avec 2 munitions restantes et 2 evenements journalises. Le cycle Akka suit la meme sequence.

**Verdict : CONFORME**

### Scenario 2 : Tir sans autorisation

T0 -> T1 -> T2 -> T4 (T3 omis). T4 **bloque** car P4 (FireAuthorized) est vide. En Akka, CommandActor renvoie `FireAuthDenied`, cycle avorte.

**Verdict : CONFORME**

### Scenario 3 : Tir sans munition chargee

T0 -> T1 -> T3 -> T4 (T2 omis). T4 **bloque** car P3 (AmmoLoaded) est vide. En Akka, AmmoActor renvoie `AmmoLoadFailed`.

**Verdict : CONFORME**

### Scenario 4 : Erreur et recuperation

T9 -> T10. Le cycle fonctionne, retour a Idle. En Akka, strategie restart du SupervisorActor.

**Verdict : CONFORME**

### Scenario 5 : Epuisement des munitions

Cycle complet avec ammo=1, puis tentative. T0 **bloque** car P10=0. En Akka, AmmoActor renvoie `AmmoLoadFailed`.

**Verdict : CONFORME**

### Scenario 6 : Double tir consecutif

Apres T5, tentative de T5 a nouveau. **Bloque** car P5 est vide. En Akka, FireControlActor est en etat postFire.

**Verdict : CONFORME**

---

## 5.3 Recapitulatif

| Scenario | Petri Net | Akka | Verdict |
|---|:---:|:---:|---|
| Cycle nominal | Succes | Succes | CONFORME |
| Sans autorisation | Blocage T4 | Abort | CONFORME |
| Sans munition | Blocage T4 | Abort | CONFORME |
| Erreur/recuperation | Succes | Succes | CONFORME |
| Epuisement munitions | Blocage T0 | Abort | CONFORME |
| Double tir | Blocage T5 | Impossible | CONFORME |

**6/6 scenarios conformes.**

---

## 5.4 Analyse

### Differences structurelles

1. **Concurrence** : en Akka, T1/T2/T3 sont veritablement concurrents (messages asynchrones). Dans le Petri Net, ils sont entrelaces mais le resultat est identique grace a T4.
2. **Temporisation** : Akka utilise des timers reels (2s reload, 3s cooldown). Le Petri Net est non temporise.
3. **Granularite** : T0 verifie P10 >= 1 alors qu'en Akka, c'est AmmoActor qui verifie lors du LoadAmmo. Le modele est plus conservatif.

### Conclusion

Le modele formel est une **abstraction fidele** de l'implementation. Les proprietes de surete prouvees sur le reseau de Petri s'appliquent au systeme Akka, sous reserve que l'implementation respecte les contrats modelises.
