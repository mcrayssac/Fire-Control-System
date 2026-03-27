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

---

## 5.5 Commandes SBT

Le projet s'execute via **sbt** (Scala Build Tool). Voici le detail de chaque commande disponible :

### `sbt compile`

Compile l'ensemble des sources Scala du projet situees dans `src/main/scala/`. Cette commande :
- Resout et telecharge les dependances declarees dans `build.sbt` (Akka Typed, Kafka, ScalaTest, etc.)
- Compile les 8 acteurs Akka (`actors/`), les modeles de messages (`model/`), le reseau de Petri (`petri/`) et le point d'entree (`Main.scala`)
- Genere les fichiers `.class` dans le repertoire `target/`

C'est la premiere commande a executer apres le clonage du depot.

### `sbt test`

Execute l'ensemble des **tests unitaires et de verification formelle** du projet. Les suites de tests sont :

| Suite | Fichier | Ce qu'elle verifie |
|---|---|---|
| `ActorSpec` | `src/test/scala/fcs/actors/ActorSpec.scala` | Comportement des 8 acteurs Akka (envoi/reception de messages, transitions d'etats, strategie de supervision) |
| `PetriNetSpec` | `src/test/scala/fcs/petri/PetriNetSpec.scala` | Moteur generique du reseau de Petri (franchissement de transitions, pre/post-conditions, marquage) |
| `FCSPetriNetSpec` | `src/test/scala/fcs/petri/FCSPetriNetSpec.scala` | Reseau de Petri specifique au FCS (13 places, 12 transitions, marquage initial, scenarios fonctionnels) |
| `VerificationSpec` | `src/test/scala/fcs/petri/VerificationSpec.scala` | Verification formelle complete : 10 invariants metier (INV1-INV10), 6 proprietes LTL, exploration de l'espace d'etats, detection de deadlocks |
| `InvariantAnalysisSpec` | `src/test/scala/fcs/petri/InvariantAnalysisSpec.scala` | Analyse structurelle : P-invariants, T-invariants, bornitude, vivacite |

Cette commande ne necessite **pas** de serveur Kafka et se lance sans prerequis externe.

### `sbt "run akka-demo"`

Lance une **demonstration interactive** du systeme distribue Akka/Kafka. Concretement :
1. Cree un `ActorSystem` avec le `SupervisorActor` comme acteur racine
2. Demarre le systeme (`StartSystem`) : les 8 acteurs sont crees et supervises
3. Execute un scenario de tir nominal (`NominalFireCycle`) : detection de cible -> verrouillage -> chargement munition -> autorisation -> synchronisation -> tir -> rechargement -> refroidissement
4. Chaque evenement est journalise via le `KafkaProducerActor` (en mode local si Kafka n'est pas disponible)
5. Le programme attend que l'utilisateur appuie sur **ENTREE** pour arreter le systeme

Cette commande permet d'**observer en temps reel** le flux de messages entre les acteurs dans la console.

### `sbt "run conformance"`

Execute la **verification de conformite** entre le modele formel (Petri Net) et l'implementation (Akka). Le processus se deroule en trois phases :

1. **Phase 1 — Simulation Petri Net** : execute les 6 scenarios (nominal, sans autorisation, sans munition, erreur, epuisement, double tir) sur le reseau de Petri et enregistre les traces de transitions franchies
2. **Phase 2 — Simulation Akka** : lance les memes scenarios dans des `ActorSystem` dedies et collecte les evenements produits par le `KafkaProducerActor`
3. **Phase 3 — Comparaison** : compare chaque trace Akka avec la trace Petri Net correspondante via le mapping transition/evenement (cf. section 5.1) et produit un rapport de conformite

Le resultat final indique pour chaque scenario si l'implementation est **CONFORME** ou non au modele formel.
