/*
 * Copyright 2012 Pellucid and Zenexity
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aws.s3.models

import java.util.Date

import play.api.libs.ws._

import scala.concurrent.Future
import scala.xml._

import aws.core._
import aws.core.Types._
import aws.core.parsers.Parser

import aws.s3.S3._
import aws.s3.S3.HTTPMethods._
import aws.s3.S3Parsers._

import scala.concurrent.ExecutionContext.Implicits.global

import aws.s3.S3.Parameters.Permisions.Grantees._

case class Owner(id: String, name: Option[String])

import S3Object.StorageClasses.StorageClass

trait Container {
  val id: Option[String]
  val key: String
  val isLatest: Boolean
  val lastModified: Date
  val etag: String
  val size: Option[Long]
  val storageClass: Option[StorageClass]
  val owner: Owner
}

case class DeleteMarker(
  id: Option[String],
  key: String,
  isLatest: Boolean,
  lastModified: Date,
  etag: String,
  size: Option[Long],
  storageClass: Option[StorageClass] = Some(S3Object.StorageClasses.STANDARD),
  owner: Owner) extends Container

case class Version(
  id: Option[String],
  key: String,
  isLatest: Boolean,
  lastModified: Date,
  etag: String,
  size: Option[Long],
  storageClass: Option[StorageClass] = Some(S3Object.StorageClasses.STANDARD),
  owner: Owner) extends Container

// TODO: Add content
case class Versions(
  name: String,
  prefix: Option[String],
  key: Option[String],
  maxKeys: Long,
  isTruncated: Boolean,
  versions: Seq[Version],
  deleteMarkers: Seq[DeleteMarker],
  versionId: Option[String])

case class S3Object(
  name: String,
  prefix: Option[String],
  marker: Option[String],
  maxKeys: Long,
  isTruncated: Boolean,
  contents: Seq[Content])

case class Content(
  id: Option[String],
  key: String,
  isLatest: Boolean,
  lastModified: Date,
  etag: String,
  size: Option[Long],
  storageClass: Option[StorageClass] = Some(S3Object.StorageClasses.STANDARD),
  owner: Owner) extends Container

case class BatchDeletion(successes: Seq[BatchDeletion.DeletionSuccess], failures: Seq[BatchDeletion.DeletionFailure])
object BatchDeletion {
  case class DeletionSuccess(
    key: String,
    versionId: Option[String],
    deleteMarker: Option[String],
    deleteMarkerVersionId: Option[String])

  case class DeletionFailure(key: String, code: String, message: String)
}

object S3Object {

  import java.io.File
  import play.api.libs.iteratee._

  object StorageClasses extends Enumeration {
    type StorageClass = Value
    val STANDARD = Value
  }

  /**
  * Returns some or all (up to 1000) of the objects in a bucket.
  * To use this implementation of the operation, you must have READ access to the bucket.
  */
  def content(bucketname: String): Future[Result[S3Metadata, S3Object]] =
    Http.get[S3Object](Some(bucketname))

  /**
  * List metadata about all of the versions of objects in a bucket
  */
  def getVersions(bucketname: String): Future[Result[S3Metadata, Versions]] =
    Http.get[Versions](Some(bucketname), subresource = Some("versions"))

  /**
  * Adds an object to a bucket. You must have WRITE permissions on a bucket to add an object to it.
  * @param bucketname Name of the target bucket
  * @param body The File to upload to this Bucket
  */
  // TODO: RRS
  // TODO: ACL
  // http://aws.amazon.com/articles/1109?_encoding=UTF8&jiveRedirect=1
  // Transfer-Encoding: chunked is not supported. The PUT operation must include a Content-Length header.
  def put(bucketname: String, body: File): Future[EmptyResult[S3Metadata]] =
    Http.upload[Unit](PUT, bucketname, body.getName, body)

  /**
  * Removes the null version (if there is one) of an object and inserts a delete marker, which becomes the latest version of the object.
  * If there isn't a null version, Amazon S3 does not remove any objects.
  * @param bucketname Name of the target bucket
  * @param objectName Name of the object to delete
  * @param versionId specific object version to delete
  * @param mfa Required to permanently delete a versioned object if versioning is configured with MFA Delete enabled.
  */
  def delete(
       bucketname: String,
       objectName: String,
       versionId: Option[String] = None,
       mfa: Option[MFA] = None): Future[EmptyResult[S3Metadata]] = {
    Http.delete[Unit](Some(bucketname),
      Some(objectName),
      parameters = mfa.map{ m => Parameters.X_AMZ_MFA(m) }.toSeq,
      queryString = versionId.toSeq.map("versionId" -> _))
  }

  /**
  * Delete multiple objects from a bucket using a single HTTP request
  * @param bucketname Name of the target bucket
  * @param objects Seq of objectName -> objectVersion
  * @param mfa Required to permanently delete a versioned object if versioning is configured with MFA Delete enabled.
  */
  def batchDelete(bucketname: String, objects: Seq[(String, Option[String])], mfa: Option[MFA] = None) = {
    val b =
      <Delete>
        <Quiet>false</Quiet>
        {
          for(o <- objects) yield
          <Object>
            <Key>{ o._1 }</Key>
            { for(v <- o._2.toSeq) yield <VersionId>{ v }</VersionId> }
          </Object>
        }
      </Delete>

    val ps = Seq(Parameters.MD5(b.mkString), aws.s3.AWS.Parameters.ContentLength(b.mkString.length)) ++
      mfa.map(m => Parameters.X_AMZ_MFA(m)).toSeq

    Http.post[Node, BatchDeletion](Some(bucketname),
      body = b,
      subresource = Some("delete"),
      parameters = ps)
  }


}
