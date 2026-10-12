//! Plans: what an attempt writes (write-path contract section 3).

use std::fmt;

use fw_core::{BoxFuture, ClientId};
use fw_db::{DbError, Tx};

use crate::aggregate::{Aggregate, Loaded, Version, VersionCheck, Write};
use crate::dispatch::DispatchJob;
use crate::event::{DomainEvent, ErasedEvent};
use crate::repository::Repository;

/// What applying one write gave.
pub(crate) enum Applied {
    Done,
    /// Row count 0: someone else committed a change since the aggregate was loaded.
    Stale,
    /// Row count above 1: a bug in the repository.
    TooMany(u64),
}

/// One planned write, its type erased.
pub(crate) trait Persist: Send + Sync {
    fn client(&self) -> &ClientId;
    fn entity(&self) -> (&'static str, &str);
    fn apply<'t>(&'t self, tx: &'t mut Tx) -> BoxFuture<'t, Result<Applied, DbError>>;
}

fn next(v: Version) -> VersionCheck {
    VersionCheck {
        expected: v,
        next: Version::stored(v.get().saturating_add(1)).unwrap_or(v),
    }
}

fn count(rows: u64) -> Applied {
    match rows {
        0 => Applied::Stale,
        1 => Applied::Done,
        n => Applied::TooMany(n),
    }
}

struct Save<'a, A, R> {
    write: Write<A>,
    repo: &'a R,
}

impl<A: Aggregate, R: Repository<A>> Persist for Save<'_, A, R> {
    fn client(&self) -> &ClientId {
        match &self.write {
            Write::Insert(f) => &f.client,
            Write::Update(l) => &l.client,
        }
    }

    fn entity(&self) -> (&'static str, &str) {
        let a = match &self.write {
            Write::Insert(f) => &f.value,
            Write::Update(l) => &l.value,
        };
        (A::entity_type(), a.id().as_ref())
    }

    fn apply<'t>(&'t self, tx: &'t mut Tx) -> BoxFuture<'t, Result<Applied, DbError>> {
        Box::pin(async move {
            match &self.write {
                Write::Insert(f) => {
                    self.repo.insert(tx, &f.client, &f.value).await?;
                    Ok(Applied::Done)
                }
                Write::Update(l) => Ok(count(
                    self.repo
                        .update(tx, &l.client, &l.value, next(l.version))
                        .await?,
                )),
            }
        })
    }
}

struct Delete<'a, A, R> {
    loaded: Loaded<A>,
    repo: &'a R,
}

impl<A: Aggregate, R: Repository<A>> Persist for Delete<'_, A, R> {
    fn client(&self) -> &ClientId {
        &self.loaded.client
    }

    fn entity(&self) -> (&'static str, &str) {
        (A::entity_type(), self.loaded.value.id().as_ref())
    }

    fn apply<'t>(&'t self, tx: &'t mut Tx) -> BoxFuture<'t, Result<Applied, DbError>> {
        Box::pin(async move {
            let l = &self.loaded;
            Ok(count(
                self.repo
                    .delete(tx, &l.client, &l.value, next(l.version))
                    .await?,
            ))
        })
    }
}

pub(crate) enum StepEvent<'a> {
    /// The plan's result event.
    Result,
    Other(Box<dyn ErasedEvent + 'a>),
}

pub(crate) struct Step<'a> {
    pub(crate) write: Option<Box<dyn Persist + 'a>>,
    pub(crate) event: StepEvent<'a>,
}

/// What a use case's `execute` decided to write: aggregates in order, an event per step (the first
/// is the use case's result), and dispatch jobs. Built with [`Plan::save`], [`Plan::delete`] or
/// [`Plan::emit`], then `and_*`. Only the runner applies a plan.
pub struct Plan<'a, E> {
    pub(crate) result: E,
    pub(crate) steps: Vec<Step<'a>>,
    pub(crate) jobs: Vec<DispatchJob>,
}

impl<E: fmt::Debug> fmt::Debug for Plan<'_, E> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Plan")
            .field("result", &self.result)
            .field("steps", &self.steps.len())
            .field("jobs", &self.jobs.len())
            .finish()
    }
}

impl<'a, E: DomainEvent + 'a> Plan<'a, E> {
    /// Saves an aggregate (an insert for [`crate::Fresh`], an update for [`Loaded`]) with the
    /// result event.
    pub fn save<A, R>(write: impl Into<Write<A>>, repo: &'a R, event: E) -> Self
    where
        A: Aggregate + 'a,
        R: Repository<A>,
    {
        Self {
            result: event,
            steps: vec![Step {
                write: Some(Box::new(Save {
                    write: write.into(),
                    repo,
                })),
                event: StepEvent::Result,
            }],
            jobs: Vec::new(),
        }
    }

    /// Deletes a loaded aggregate, with the result event.
    pub fn delete<A, R>(aggregate: Loaded<A>, repo: &'a R, event: E) -> Self
    where
        A: Aggregate + 'a,
        R: Repository<A>,
    {
        Self {
            result: event,
            steps: vec![Step {
                write: Some(Box::new(Delete {
                    loaded: aggregate,
                    repo,
                })),
                event: StepEvent::Result,
            }],
            jobs: Vec::new(),
        }
    }

    /// Writes only the result event.
    pub fn emit(event: E) -> Self {
        Self {
            result: event,
            steps: vec![Step {
                write: None,
                event: StepEvent::Result,
            }],
            jobs: Vec::new(),
        }
    }

    /// Saves another aggregate, with its event.
    #[must_use]
    pub fn and_save<A, R>(
        mut self,
        write: impl Into<Write<A>>,
        repo: &'a R,
        event: impl DomainEvent + 'a,
    ) -> Self
    where
        A: Aggregate + 'a,
        R: Repository<A>,
    {
        self.steps.push(Step {
            write: Some(Box::new(Save {
                write: write.into(),
                repo,
            })),
            event: StepEvent::Other(Box::new(event)),
        });
        self
    }

    /// Deletes another loaded aggregate, with its event.
    #[must_use]
    pub fn and_delete<A, R>(
        mut self,
        aggregate: Loaded<A>,
        repo: &'a R,
        event: impl DomainEvent + 'a,
    ) -> Self
    where
        A: Aggregate + 'a,
        R: Repository<A>,
    {
        self.steps.push(Step {
            write: Some(Box::new(Delete {
                loaded: aggregate,
                repo,
            })),
            event: StepEvent::Other(Box::new(event)),
        });
        self
    }

    /// Writes another event.
    #[must_use]
    pub fn and_emit(mut self, event: impl DomainEvent + 'a) -> Self {
        self.steps.push(Step {
            write: None,
            event: StepEvent::Other(Box::new(event)),
        });
        self
    }

    /// Writes a dispatch job (after the events, before the audit row).
    #[must_use]
    pub fn and_dispatch(mut self, job: DispatchJob) -> Self {
        self.jobs.push(job);
        self
    }

    /// The result event.
    #[must_use]
    pub fn result(&self) -> &E {
        &self.result
    }
}
