# Fire Control System (FCS)

Modélisation formelle et vérification d'un système de contrôle de tir pour véhicule blindé.

Le système combine :
- Un **réseau de Pétri** (13 places, 12 transitions) modélisant le cycle de tir
- Une **vérification formelle** : 10 invariants métier (INV1-INV10) + 6 propriétés LTL
- Une **implémentation distribuée** en Scala/Akka (modèle d'acteurs) avec bus Kafka

## Prérequis

| Outil | Version |
|-------|---------|
| JDK | 21+ |
| sbt | 1.10+ |
| Apache Kafka | 3.x *(optionnel, uniquement pour le mode simulation complète)* |

## Installation

```bash
git clone <url-du-repo>
cd Fire-Control-System
sbt compile
```

## Lancer le projet

Trois modes d'exécution :

```bash
# Vérification formelle du réseau de Pétri (mode par défaut)
sbt "run verify"

# Simulation Akka/Kafka du système FCS
sbt "run simulate"

# Simulation comparée : Akka vs modèle formel
sbt "run compare"
```

### Mode `verify` (défaut)

Exécute dans l'ordre :
1. Construction du réseau de Pétri (13 places, 12 transitions)
2. Exploration BFS de l'espace d'états
3. Vérification des 10 invariants métier (INV1-INV10)
4. Vérification des 6 propriétés LTL
5. Recherche du chemin nominal vers l'état Firing

### Mode `simulate`

Lance le système d'acteurs Akka avec un scénario de tir nominal. Nécessite un appui sur ENTRÉE pour arrêter.

### Mode `compare`

Exécute le cycle de tir en parallèle sur le modèle formel et le système Akka pour comparer les résultats.

## Lancer les tests

```bash
# Tous les tests (31 tests)
sbt test

# Tests de vérification formelle uniquement
sbt "testOnly fcs.petri.*"
```

## Structure du projet

```
Fire-Control-System/
├── build.sbt
├── src/
│   ├── main/scala/fcs/
│   │   ├── Main.scala                    # Point d'entrée (verify / simulate / compare)
│   │   ├── actors/
│   │   │   ├── SensorActor.scala         # Détection de cibles
│   │   │   ├── TrackingActor.scala       # Verrouillage balistique
│   │   │   ├── AmmoActor.scala           # Gestion des munitions
│   │   │   ├── CommandActor.scala        # Autorisation de tir (ROE)
│   │   │   ├── FireControlActor.scala    # Orchestrateur central
│   │   │   ├── KafkaProducerActor.scala  # Publication Kafka
│   │   │   ├── KafkaConsumerActor.scala  # Audit trail
│   │   │   └── SupervisorActor.scala     # Supervision et tolérance aux pannes
│   │   ├── model/
│   │   │   ├── Messages.scala            # Protocoles de messages inter-acteurs
│   │   │   └── FCSState.scala            # États et types du système
│   │   ├── kafka/
│   │   │   ├── Topics.scala              # Définition des topics Kafka
│   │   │   └── KafkaConfig.scala         # Configuration Kafka
│   │   └── petri/
│   │       ├── PetriNet.scala            # Modèle formel (Marking, Transition, PetriNet)
│   │       ├── FCSPetriNet.scala         # Réseau de Pétri FCS (13 places, 12 transitions)
│   │       ├── StateSpaceAnalyzer.scala  # Exploration BFS/DFS de l'espace d'états
│   │       ├── InvariantChecker.scala    # Vérification INV1-INV10
│   │       └── LTLVerifier.scala         # Vérification LTL
│   ├── main/resources/
│   │   ├── application.conf              # Configuration Akka + Kafka
│   │   └── logback.xml                   # Logging
│   └── test/scala/fcs/petri/
│       ├── PetriNetSpec.scala            # Tests unitaires du modèle
│       ├── FCSPetriNetSpec.scala         # Tests du réseau FCS
│       └── VerificationSpec.scala        # Tests de vérification formelle
└── docs/
    └── fcs_petri_net.drawio              # Diagramme du réseau de Pétri
```

## Contributeurs

- Paul Pitiot
- Maxime Crayssac
- Arthur Neuez
