# 1. Etat de l'art

## 1.1 Verification formelle

La **verification formelle** consiste a prouver mathematiquement qu'un systeme respecte certaines proprietes. Contrairement au test logiciel (nombre fini de scenarios), elle explore *tous* les comportements possibles.

Dans les systemes critiques (avionique, nucleaire, militaire), une erreur logicielle peut etre catastrophique. L'echec du vol 501 d'Ariane 5 (1996), cause par un depassement d'entier non detecte, illustre ce besoin (Lions et al., 1996).

### Model checking vs. Theorem proving

| Approche | Principe | Avantages | Limites |
|---|---|---|---|
| **Model checking** | Exploration exhaustive d'un modele fini | Automatique, fournit des contre-exemples | Explosion combinatoire |
| **Theorem proving** | Deduction logique (Coq, Isabelle, Lean) | Espace d'etats infini | Expertise elevee, intervention humaine |

Nous utilisons le **model checking** : notre FCS (111 etats atteignables) reste compact pour une exploration exhaustive.

### Application au FCS

1. Reseau de Petri modelisant les etats et transitions
2. Exploration exhaustive par BFS
3. Verification de 10 invariants metier et 6 proprietes LTL
4. Calcul de P/T-invariants par elimination de Gauss
5. Analyse de bornitude et vivacite

---

## 1.2 Reseaux de Petri

### Definition formelle

Un **reseau de Petri** (Petri, 1962) est un quintuplet **N = (P, T, Pre, Post, M0)** :

- **P** : ensemble fini de **places** (cercles)
- **T** : ensemble fini de **transitions** (barres)
- **Pre : T x P -> N** : fonction de pre-condition (arcs entrants)
- **Post : T x P -> N** : fonction de post-condition (arcs sortants)
- **M0 : P -> N** : marquage initial

Les **jetons** representent l'etat courant. Un marquage M associe a chaque place son nombre de jetons.

### Semantique de franchissement

Une transition t est **franchissable** dans un marquage M ssi :

> Pour tout p dans P : M(p) >= Pre(t, p)

Le franchissement produit un nouveau marquage M' :

> Pour tout p dans P : M'(p) = M(p) - Pre(t, p) + Post(t, p)

### Matrice d'incidence

**C = Post - Pre** (matrice |P| x |T|). Equation fondamentale : **M' = M + C . sigma** (sigma = vecteur de Parikh).

### Proprietes analysables

| Propriete | Definition |
|---|---|
| **Bornitude** | k-borne si pour tout M atteignable et toute place p : M(p) <= k. 1-borne = sauf |
| **Vivacite** | L0 (morte), L1 (potentiellement franchissable), L4 (vivante depuis tout etat) |
| **Absence de deadlock** | Aucun etat sans transition franchissable |
| **P-invariant** | Vecteur y >= 0 tel que y^T . C = 0 -> y^T . M = constante (loi de conservation) |
| **T-invariant** | Vecteur x >= 0 tel que C . x = 0 -> comportement cyclique (effet net nul) |

---

## 1.3 Logique temporelle lineaire (LTL)

Les invariants statiques ne suffisent pas toujours. LTL (Pnueli, 1977) exprime des proprietes sur l'*evolution temporelle* du systeme.

### Operateurs temporels

| Operateur | Notation | Signification |
|---|:---:|---|
| **Globally** | G(phi) | phi est vraie dans *tous* les etats futurs |
| **Finally** | F(phi) | phi sera vraie dans *au moins un* etat futur |
| **Next** | X(phi) | phi est vraie dans l'etat immediatement suivant |
| **Until** | phi U psi | phi reste vraie jusqu'a ce que psi devienne vraie |

### Types de proprietes

- **Surete** (*safety*) : "quelque chose de mauvais n'arrive jamais" -> operateur G
  - Exemple : `G(not(firing et reloading))`
- **Vivacite** (*liveness*) : "quelque chose de bon finit par arriver" -> operateur F ou G(F(...))
  - Exemple : `G(error -> F(idle))`

### Verification par model checking

- `G(phi)` : verifier phi dans chaque etat atteignable
- `G(F(phi))` : depuis chaque etat, un etat satisfaisant phi est toujours atteignable
- En cas de violation : **contre-exemple** (chemin d'execution fautif)

---

## 1.4 Modele d'acteurs et reseaux de Petri

Le **modele d'acteurs** (Hewitt, 1973) est un paradigme concurrent ou chaque entite :
- Possede son propre etat interne (pas de memoire partagee)
- Communique par envoi de messages asynchrones
- Traite un seul message a la fois

**Akka** (Lightbend) implemente ce modele en Scala avec supervision hierarchique et typage des messages.

### Correspondance acteurs <-> reseau de Petri

| Concept Akka | Concept Petri Net |
|---|---|
| Etat d'un acteur | Place contenant un jeton |
| Reception d'un message | Transition |
| Message entre acteurs | Arc |
| Ressource partagee (munitions) | Place ressource (plusieurs jetons) |
| Synchronisation (attente de N conditions) | Transition de synchronisation |

L'interet de combiner les deux : le reseau de Petri **prouve** l'absence de deadlocks (exhaustivite), la simulation Akka **valide** le comportement reel (realisme).

---

## References

1. Murata, T. (1989). *Petri Nets: Properties, Analysis and Applications*. Proceedings of the IEEE, 77(4).
2. Baier, C. & Katoen, J.-P. (2008). *Principles of Model Checking*. MIT Press.
3. Clarke, E. M., Grumberg, O. & Peled, D. A. (1999). *Model Checking*. MIT Press.
4. Pnueli, A. (1977). *The Temporal Logic of Programs*. 18th FOCS, IEEE.
5. Hewitt, C., Bishop, P. & Steiger, R. (1973). *A Universal Modular ACTOR Formalism for AI*. IJCAI'73.
6. Agha, G. (1986). *Actors: A Model of Concurrent Computation in Distributed Systems*. MIT Press.
7. Lightbend (2024). *Akka Documentation*. https://doc.akka.io/
8. Lions, J.-L. et al. (1996). *Ariane 5 Flight 501 Failure Report*. ESA/CNES.
9. Peterson, J. L. (1981). *Petri Net Theory and the Modeling of Systems*. Prentice-Hall.
